package pkmx.lcamera

import collection.JavaConversions._
import java.io.{File, FileOutputStream}
import java.nio.ByteBuffer
import java.text.DecimalFormat
import scala.concurrent.{Promise, Channel, ExecutionContext, Future}
import scala.collection.immutable.Vector
import scala.language.{existentials, higherKinds, implicitConversions, reflectiveCalls}
import scala.util.control.NonFatal

import android.animation._
import android.content.{Intent, DialogInterface, Context}
import android.graphics._
import android.graphics.drawable.ColorDrawable
import android.hardware.Camera.{ACTION_NEW_PICTURE, ACTION_NEW_VIDEO}
import android.hardware.camera2._
import android.hardware.camera2.CameraCharacteristics._
import android.hardware.camera2.CameraMetadata._
import android.hardware.camera2.CaptureRequest._
import android.hardware.camera2.params._
import android.media.{MediaRecorder, Image, MediaScannerConnection, ImageReader}
import android.media.ImageReader.OnImageAvailableListener
import android.media.MediaScannerConnection.OnScanCompletedListener
import android.net.Uri
import android.os._
import android.text.format.Time
import android.view._
import android.view.animation.{Animation, TranslateAnimation}
import android.view.animation.Animation.AnimationListener
import android.widget._
import android.widget.ImageView.ScaleType
import android.util.{Log, Size}

import com.melnykov.fab.FloatingActionButton
import org.scaloid.common._
import rx._
import rx.ops._

object Utils {
  implicit val execCtx = ExecutionContext.fromExecutor(AsyncTask.THREAD_POOL_EXECUTOR)

  implicit def requestKeyCovariant[_ <: Any](k: CaptureRequest.Key[_]): CaptureRequest.Key[Any] = k.asInstanceOf[CaptureRequest.Key[Any]]
  implicit def resultKeyCovariant[_ <: Any](k: CaptureRequest.Key[_]): CaptureResult.Key[Any] = k.asInstanceOf[CaptureResult.Key[Any]]

  type Fab = FloatingActionButton

  val circularReveal = (v: View, cx: Int, cy: Int, r: Int) => {
    val anim = ViewAnimationUtils.createCircularReveal(v, cx, cy, 0, r)
    anim.addListener(new AnimatorListenerAdapter() {
      override def onAnimationStart(animator: Animator) {
        super.onAnimationStart(animator)
        v.visibility = View.VISIBLE
      }
    })
    anim
  }

  val circularHide = (v: View, cx: Int, cy: Int, r: Int) => {
    val anim = ViewAnimationUtils.createCircularReveal(v, cx, cy, r, 0)
    anim.addListener(new AnimatorListenerAdapter() {
      override def onAnimationEnd(animator: Animator) {
        super.onAnimationEnd(animator)
        v.visibility = View.INVISIBLE
      }
    })
    anim
  }

  val slideDownHide = (v: View) => {
    v.startAnimation(new TranslateAnimation(
      Animation.RELATIVE_TO_SELF, 0,
      Animation.RELATIVE_TO_SELF, 0,
      Animation.RELATIVE_TO_SELF, 0,
      Animation.RELATIVE_TO_SELF, 1) {
      setDuration(300)
      setAnimationListener(new AnimationListener {
        override def onAnimationEnd(anim: Animation) { v.visibility = View.INVISIBLE }
        override def onAnimationStart(anim: Animation) {}
        override def onAnimationRepeat(anim: Animation) {}
      })
    })
  }

  def NoneVar[T] = Var[Option[T]](None)

  class STextureView(implicit ctx: Context) extends TextureView(ctx) with TraitView[TextureView] {
    val basis = this
  }

  class SSwitch(implicit ctx: Context) extends Switch(ctx) with TraitCompoundButton[Switch] {
    val basis = this
  }

  class MyMediaRecorder(val vc: VideoConfiguration, orientation: Int) extends MediaRecorder {
    import MyMediaRecorder._
    var realFilePath = tmpFilePath

    setVideoSource(2) // SURFACE
    setAudioSource(5) // CAMCORDER
    setOutputFormat(2) // MPEG_4
    setAudioChannels(2)
    setAudioEncodingBitRate(384000)
    setAudioSamplingRate(44100)
    setVideoSize(vc.width, vc.height)
    setVideoEncodingBitRate(vc.bitrate)
    setVideoFrameRate(vc.fps)
    setOrientationHint(orientationToDegree(orientation))
    setOutputFile(tmpFilePath)
    setVideoEncoder(2) // H264
    setAudioEncoder(3) // AAC
    prepare()
  }

  object MyMediaRecorder {
    val tmpFilePath = createPathIfNotExist(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/Camera/") + ".lcamera.mp4"
  }

  def renameFile(src: String, dst: String) { new File(src).renameTo(new File(dst)) }

  def orientationToDegree(orientation: Int) = orientation match {
    case Surface.ROTATION_0 => 90
    case Surface.ROTATION_90 => 0
    case Surface.ROTATION_180 => 270
    case Surface.ROTATION_270 => 180
    case _ => 0
  }

  def createPathIfNotExist(path: String): String = {
    val file = new File(path)
    if (!file.exists()) {
      file.mkdirs()
    }
    path
  }

  implicit class RichSize(size: Size) {
    def <=(rhs: Size) = size.getWidth <= rhs.getWidth && size.getHeight <= rhs.getHeight
  }

  sealed case class VideoConfiguration(width: Int, height: Int, fps: Int, bitrate: Int) {
    override def toString: String = s"${width}x${height}x$fps @ ${new DecimalFormat("#.#").format(bitrate.toDouble / 1000000)}mbps"
  }

  sealed trait Observable extends AutoCloseable {
    var obses: List[Obs] = List()

    def observe(obs: Obs): Obs = {
      obses = obs :: obses
      obs
    }

    override def close() {
      obses foreach { _.kill() }
    }
  }

  def byteBufferToByteArray(bb: ByteBuffer): Array[Byte] = {
    val bytes = new Array[Byte](bb.remaining())
    bb.get(bytes, 0, bb.remaining())
    bytes
  }

  def mediaScan(filePath: String, action: String)(implicit ctx: Context) {
    MediaScannerConnection.scanFile(ctx, Array(filePath), null, new OnScanCompletedListener {
      override def onScanCompleted(path: String, uri: Uri) {
        if (uri != null) {
          ctx.sendBroadcast(new Intent(action, uri))
        } else {
          ctx.sendBroadcast(new Intent(action, Uri.fromFile(new File(path))))
        }
      }
    })
  }

  implicit class RichFuture[+T](val future: Future[T]) {
    def toOption: Option[T] = future.value flatMap { _.toOption }
  }

  implicit class RichRxOps[+T](val rx: Rx[T]) {
    def withFilter(f: T => Boolean) = rx filter f
  }
}

import Utils._

class LCameraManager(implicit private[this] val cameraManager: CameraManager, loggerTag: LoggerTag, ctx: Context) {
  def openLCamera (cameraId: String) : Rx[Option[LCamera]] = {
    val lcamera = NoneVar[LCamera]

    cameraManager.openCamera(cameraId, new CameraDevice.StateCallback {
      override def onOpened(device: CameraDevice) {
        debug(s"Camera opened: $device")
        lcamera() = Option(new LCamera(device))
      }

      override def onError(device: CameraDevice, error: Int) {
        debug(s"Camera error: $device, $error")
        longToast(s"Unable to open camera ($error)")
        lcamera() foreach { _.close() }
      }

      override def onDisconnected(device: CameraDevice) {
        debug(s"Camera disconnected: $device")
        lcamera() foreach { _.close() }
      }

      override def onClosed(device: CameraDevice) {
        debug(s"Camera closed: $device")
        lcamera() = None
      }
    }, null)

    lcamera
  }
}

object LCamera {
  sealed trait RawOrYuv
  case object Raw extends RawOrYuv
  case object Yuv extends RawOrYuv

  sealed trait Focus
  case object AutoFocus extends Focus
  case class ManualFocus(focusDistance: Float) extends Focus

  sealed trait Exposure
  case object AutoExposure extends Exposure
  case class ManualExposure(exposureDuration: Long, iso: Int) extends Exposure
}

class LCamera (private[this] val camera: CameraDevice) (implicit cameraManager: CameraManager, loggerTag: LoggerTag, ctx: Context) extends Observable with AutoCloseable {
  import LCamera._

  val characteristics = cameraManager.getCameraCharacteristics(camera.getId)
  val streamConfigurationMap = characteristics.get(SCALER_STREAM_CONFIGURATION_MAP)
  val activeArraySize = characteristics.get(SENSOR_INFO_ACTIVE_ARRAY_SIZE)
  val minFocusDistance = characteristics.get(LENS_INFO_MINIMUM_FOCUS_DISTANCE)
  val isoRange = characteristics.get(SENSOR_INFO_SENSITIVITY_RANGE)
  val exposureTimeRange = characteristics.get(SENSOR_INFO_EXPOSURE_TIME_RANGE)

  val yuvSizes = streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)
  val rawSize = streamConfigurationMap.getOutputSizes(ImageFormat.RAW_SENSOR)(0)
  val jpegSize = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG).filter(_ <= rawSize)(0)
  val yuvSize = yuvSizes.filter(_ <= rawSize)(0)
  val minFrameDuration = streamConfigurationMap.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, rawSize)
  val rawFps = if (minFrameDuration != 0) 1000000000 / minFrameDuration else Int.MaxValue

  private[this] val captureSessionVar = NoneVar[Future[CaptureSession]]
  def captureSession: Rx[Option[Future[CaptureSession]]] = captureSessionVar

  sealed trait CaptureSession extends AutoCloseable {
    protected[this] val session: CameraCaptureSession
    protected[this] val previewSurface: Surface

    protected[this] def setupRequest(request: CaptureRequest.Builder, focus: Focus, exposure: Exposure) {
      request.set(CONTROL_MODE, CONTROL_MODE_AUTO)
      focus match {
        case AutoFocus =>
          request.set(CONTROL_AF_MODE, CONTROL_AF_MODE_AUTO)
        case ManualFocus(fd) =>
          request.set(CONTROL_AF_MODE, CONTROL_AF_MODE_OFF)
          request.set(LENS_FOCUS_DISTANCE, fd)
      }

      exposure match {
        case AutoExposure =>
          request.set(CONTROL_AE_MODE, CONTROL_AE_MODE_ON)
        case ManualExposure(duration, iso) =>
          request.set(CONTROL_AE_MODE, CONTROL_AE_MODE_OFF)
          request.set(SENSOR_EXPOSURE_TIME, duration)
          request.set(SENSOR_SENSITIVITY, iso)
      }
      request.set(SENSOR_FRAME_DURATION, minFrameDuration)
    }

    def setupPreview(focus: Focus, exposure: Exposure) {
      debug(s"Starting preview using $session")
      val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      setupRequest(request, focus, exposure)

      request.addTarget(previewSurface)
      session.setRepeatingRequest(request.build(), null, null)
    }

    def triggerMetering(mr: MeteringRectangle, focus: Focus, exposure: Exposure) {
      debug(s"Triggering metering using $camera")
      val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

      focus match {
        case AutoFocus =>
          request.set(CONTROL_AF_MODE, CONTROL_AF_MODE_AUTO)
          request.set(CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_START)
          request.set(CONTROL_AF_REGIONS, Array[MeteringRectangle](mr))
        case ManualFocus(fd) =>
          request.set(CONTROL_AF_MODE, CONTROL_AF_MODE_OFF)
          request.set(LENS_FOCUS_DISTANCE, fd)
      }

      exposure match {
        case AutoExposure =>
          request.set(CONTROL_AE_MODE, CONTROL_AE_MODE_ON)
          request.set(CONTROL_AE_PRECAPTURE_TRIGGER, CONTROL_AE_PRECAPTURE_TRIGGER_START)
          request.set(CONTROL_AE_REGIONS, Array[MeteringRectangle](mr))
        case ManualExposure(duration, iso) =>
          request.set(CONTROL_AE_MODE, CONTROL_AE_MODE_OFF)
          request.set(SENSOR_EXPOSURE_TIME, duration)
          request.set(SENSOR_SENSITIVITY, iso)
      }
      request.addTarget(previewSurface)

      session.capture(request.build(), null, null)
    }
    override def close() {
      session.close()
    }
  }

  private[this] def createSession(surfaces: List[Surface], onSuccess: CameraCaptureSession => CaptureSession) {
    if (!(captureSessionVar() exists { !_.isCompleted } )) {
      debug(s"Creating capture session using $camera $surfaces")
      captureSession() foreach { _ onSuccess { case s => s.close() } }

      val p = Promise[CaptureSession]()
      captureSessionVar() = Some(p.future)
      camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback {
        override def onConfigured(session: CameraCaptureSession) {
          debug(s"Capture session configured: $session")
          p success onSuccess(session)
          captureSessionVar.propagate()
        }

        override def onConfigureFailed(session: CameraCaptureSession) {
          debug("Capture session configuration failed")
          p failure new RuntimeException
          captureSessionVar.propagate()
        }

        override def onClosed(session: CameraCaptureSession) {
          debug(s"Capture session closed: $session")
        }
      }, null)
    }
  }

  def openPhotoSession(previewSurface: Surface, maxImages: Int = 1) {
    val jpegImageReader = ImageReader.newInstance(jpegSize.getWidth, jpegSize.getHeight, ImageFormat.JPEG, maxImages)
    val rawImageReader = ImageReader.newInstance(rawSize.getWidth, rawSize.getHeight, ImageFormat.RAW_SENSOR, maxImages)
    createSession(List(previewSurface, jpegImageReader.getSurface, rawImageReader.getSurface), (session) => new PhotoSession(session, previewSurface, jpegImageReader, rawImageReader))
  }

  def openBurstSession(previewSurface: Surface, rawYuv: RawOrYuv, maxImages: Int = 7) {
    val imageReader = rawYuv match {
      case Raw => ImageReader.newInstance(rawSize.getWidth, rawSize.getHeight, ImageFormat.RAW_SENSOR, maxImages)
      case Yuv => ImageReader.newInstance(yuvSize.getWidth, yuvSize.getHeight, ImageFormat.YUV_420_888, maxImages)
    }
    createSession(List(previewSurface, imageReader.getSurface), (session) => new BurstSession(session, previewSurface, imageReader, rawYuv))
  }

  def openBulbSession(previewSurface: Surface) {
    val imageReader = ImageReader.newInstance(rawSize.getWidth, rawSize.getHeight, ImageFormat.RAW_SENSOR, 1)
    createSession(List(previewSurface, imageReader.getSurface), (session) => new BulbSession(session, previewSurface, imageReader))
  }

  def openVideoSession(previewSurface: Surface, vc: VideoConfiguration) {
    val mr = new MyMediaRecorder(vc, windowManager.getDefaultDisplay.getRotation)
    createSession(List(previewSurface, mr.getSurface), (session) => new VideoSession(session, previewSurface, mr))
  }

  class PhotoSession (protected[this] val session: CameraCaptureSession, protected[this] val previewSurface: Surface, jpegImageReader: ImageReader, rawImageReader: ImageReader) extends CaptureSession {
    private [this] val capturingVar = Var(false)
    def capturing: Rx[Boolean] = capturingVar
    private [this] val jpegSurface = jpegImageReader.getSurface
    private [this] val rawSurface = rawImageReader.getSurface
    private [this] val jpegImageChannel = new Channel[Image]
    private [this] val rawImageChannel = new Channel[Image]

    jpegImageReader.setOnImageAvailableListener(new OnImageAvailableListener {
      override def onImageAvailable(reader: ImageReader) { jpegImageChannel.write(reader.acquireNextImage()) }
    }, null)

    rawImageReader.setOnImageAvailableListener(new OnImageAvailableListener {
      override def onImageAvailable(reader: ImageReader) { rawImageChannel.write(reader.acquireNextImage()) }
    }, null)

    def capture(focus: Focus, exposure: Exposure, successHandler: (TotalCaptureResult, Image, Image) => Unit, orientation: Int = windowManager.getDefaultDisplay.getRotation) {
      if (!capturing()) {
        debug(s"Starting capture using $camera")
        capturingVar() = true

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        setupRequest(request, focus, exposure)
        request.set(LENS_OPTICAL_STABILIZATION_MODE, LENS_OPTICAL_STABILIZATION_MODE_ON)
        request.set(JPEG_QUALITY, 100.toByte)
        request.set(JPEG_ORIENTATION, orientationToDegree(orientation))
        request.set(STATISTICS_LENS_SHADING_MAP_MODE, STATISTICS_LENS_SHADING_MAP_MODE_ON) // Required for RAW capture
        List(previewSurface, jpegSurface, rawSurface) foreach request.addTarget

        debug(s"Capturing with $session")
        session.capture(request.build(), new CameraCaptureSession.CaptureCallback {
          override def onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            debug(s"Capture completed: " +
              s"focus = ${result.get(CaptureResult.LENS_FOCUS_DISTANCE)}/${request.get(LENS_FOCUS_DISTANCE)} " +
              s"iso = ${result.get(CaptureResult.SENSOR_SENSITIVITY)}/${request.get(SENSOR_SENSITIVITY)} " +
              s"exposure = ${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)}/${request.get(SENSOR_EXPOSURE_TIME)}")

            val f = Future {
              val jpegImage = jpegImageChannel.read
              val rawImage = rawImageChannel.read

              successHandler(result, jpegImage, rawImage)

              jpegImage.close()
              rawImage.close()
            }
            f onFailure { case NonFatal(e) => e.printStackTrace() }
            f onComplete { _ => runOnUiThread { capturingVar() = false } }
          }

          override def onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            debug("Capture failed")
            capturingVar() = false
          }
        }, null)
      }
    }
  }

  class BurstSession (protected[this] val session: CameraCaptureSession, protected[this] val previewSurface: Surface, private[this] val imageReader: ImageReader, val rawYuv: RawOrYuv) extends CaptureSession {
    private[this] val capturingVar = Var(false)
    def capturing: Rx[Boolean] = capturingVar

    private [this] val surface = imageReader.getSurface
    private [this] val imageChannel = new Channel[Image]
    private [this] val resultChannel = new Channel[Either[TotalCaptureResult, CaptureFailure]]

    imageReader.setOnImageAvailableListener(new OnImageAvailableListener {
      override def onImageAvailable(reader: ImageReader) { imageChannel.write(reader.acquireNextImage()) }
    }, null)

    case class Request(focus: Focus, exposure: Exposure, handler: (TotalCaptureResult, Image) => Unit)
    def burstCapture(requests: List[Request], orientation: Int = windowManager.getDefaultDisplay.getRotation) {
      if (!capturing()) {
        debug(s"Starting burst capture using $camera")
        capturingVar() = true

        val builders = requests map { r => {
          val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
          setupRequest(request, r.focus, r.exposure)
          request.set(LENS_OPTICAL_STABILIZATION_MODE, LENS_OPTICAL_STABILIZATION_MODE_ON)
          if (rawYuv == Raw) {
            request.set (STATISTICS_LENS_SHADING_MAP_MODE, STATISTICS_LENS_SHADING_MAP_MODE_ON) // Required for RAW capture
          }
          request.addTarget(surface)
          request
        } }

        debug(s"Burst capturing with $session")
        session.captureBurst(builders map { _.build() }, new CameraCaptureSession.CaptureCallback {
          override def onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            debug(s"Capture completed: " +
              s"focus = ${result.get(CaptureResult.LENS_FOCUS_DISTANCE)}/${request.get(LENS_FOCUS_DISTANCE)} " +
              s"iso = ${result.get(CaptureResult.SENSOR_SENSITIVITY)}/${request.get(SENSOR_SENSITIVITY)} " +
              s"exposure = ${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)}/${request.get(SENSOR_EXPOSURE_TIME)}")

            resultChannel.write(Left(result))
          }

          override def onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            debug("Capture failed")
            resultChannel.write(Right(failure))
          }
        }, null)

        val f = Future {
          for (r <- requests) {
            resultChannel.read match {
              case Left(result) =>
                val image = imageChannel.read
                r.handler(result, image)
                image.close()
              case Right(_) =>
            }
          }
        }
        f onFailure { case NonFatal(e) => e.printStackTrace() }
        f onComplete { _ => runOnUiThread { capturingVar() = false } }
      }
    }
  }

  class BulbSession (protected[this] val session: CameraCaptureSession, protected[this] val previewSurface: Surface, private[this] val imageReader: ImageReader) extends CaptureSession {
    private[this] var keepCapturing = false
    private[this] val capturingVar = Var(false)
    def capturing: Rx[Boolean] = capturingVar

    private [this] val surface = imageReader.getSurface
    private [this] val imageChannel = new Channel[Image]

    imageReader.setOnImageAvailableListener(new OnImageAvailableListener {
      override def onImageAvailable(reader: ImageReader) { imageChannel.write(reader.acquireNextImage()) }
    }, null)

    def startCapturing(focus: Focus, exposure: Exposure, handler: (TotalCaptureResult, Image, Int) => Unit, orientation: Int = windowManager.getDefaultDisplay.getRotation) {
      if (!capturing()) {
        debug(s"Starting bulb capture using $camera")
        capturingVar() = true
        keepCapturing = true

        lazy val captureOne: Int => Unit = (n: Int) => {
          if (keepCapturing) {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            setupRequest(request, focus, exposure)
            request.set(LENS_OPTICAL_STABILIZATION_MODE, LENS_OPTICAL_STABILIZATION_MODE_ON)
            request.set(STATISTICS_LENS_SHADING_MAP_MODE, STATISTICS_LENS_SHADING_MAP_MODE_ON) // Required for RAW capture
            List(previewSurface, surface) foreach request.addTarget

            debug(s"bulb capturing with $session")
            session.capture(request.build, new CameraCaptureSession.CaptureCallback {
              override def onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                debug(s"Capture completed: " +
                  s"focus = ${result.get(CaptureResult.LENS_FOCUS_DISTANCE)}/${request.get(LENS_FOCUS_DISTANCE)} " +
                  s"iso = ${result.get(CaptureResult.SENSOR_SENSITIVITY)}/${request.get(SENSOR_SENSITIVITY)} " +
                  s"exposure = ${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)}/${request.get(SENSOR_EXPOSURE_TIME)}")

                Future {
                  val image = imageChannel.read
                  handler(result, image, n)
                  image.close()

                  runOnUiThread {
                    if (keepCapturing) {
                      captureOne(n + 1)
                    } else {
                      capturingVar() = false
                    }
                  }
                }
              }

              override def onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                debug("Capture failed")
                capturingVar() = false
                keepCapturing = false
              }
            }, null)
          }
        }

        captureOne(0)
      }
    }

    def stopCapturing(): Unit = {
      keepCapturing = false
    }
  }

  class VideoSession (protected[this] val session: CameraCaptureSession, protected[this] val previewSurface: Surface, private[this] val mr: MyMediaRecorder) extends CaptureSession {
    private[this] val recordingVar = Var(false)
    def recording: Rx[Boolean] = recordingVar

    override def setupPreview(focus: Focus, exposure: Exposure) {
      debug(s"Starting preview using $session")
      val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
      setupRequest(request, focus, exposure)

      List(previewSurface, mr.getSurface) foreach request.addTarget
      session.setRepeatingRequest(request.build(), null, null)
    }

    def startRecording(focus: Focus, exposure: Exposure) {
      setupPreview(focus, exposure)

      debug("Starting recording")
      val time = new Time
      time.setToNow()
      mr.realFilePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/Camera/" + time.format("VID_%Y%m%d_%H%M%S.mp4")
      mr.start()
      recordingVar() = true
    }

    def stopRecording() {
      debug("Stop recording")
      try {
        mr.stop()
        renameFile(MyMediaRecorder.tmpFilePath, mr.realFilePath)
        mediaScan(mr.realFilePath, ACTION_NEW_VIDEO)
      } catch {
        case e: RuntimeException => new File(MyMediaRecorder.tmpFilePath).delete()
      }
      recordingVar() = false
    }

    override def close() {
      super.close()
      mr.reset()
      mr.release()
    }
  }

  override def close() {
    super.close()
    captureSession() foreach { _ onSuccess { case s => s.close() } }
    camera.close()
  }
}

class MainActivity extends SActivity with Observable {
  import LCamera._

  override implicit val loggerTag = LoggerTag("lcamera")
  implicit lazy val cameraManager = getSystemService(Context.CAMERA_SERVICE).asInstanceOf[CameraManager]
  val cameraId = Var("0")
  val lcamera = NoneVar[LCamera]
  lazy val previewSurface = Rx { for { camera <- lcamera() ; texture <- textureView.surfaceTexture() } yield {
    val textureSize = camera.streamConfigurationMap.getOutputSizes(texture.getClass).filter(sz => sz <= camera.rawSize && sz <= new Size(1920, 1080))(0)
    texture.setDefaultBufferSize(textureSize.getWidth, textureSize.getHeight)
    new Surface(texture)
  }}

  val orientation = Var(Surface.ROTATION_0)
  observe { for { (rotation, cameraOption, _) <- Rx { (orientation(), lcamera(), previewSurface()) } ; camera <- cameraOption } {
    if (textureView.isAvailable) {
      textureView.setTransform {
        val textureSize = camera.streamConfigurationMap.getOutputSizes(textureView.getSurfaceTexture.getClass).filter(sz => sz <= camera.rawSize && sz <= new Size(1920, 1080))(0)
        val viewRect = new RectF(0, 0, textureView.width, textureView.height)
        val bufferRect = new RectF(0, 0, textureSize.getHeight, textureSize.getWidth)
        bufferRect.offset(viewRect.centerX - bufferRect.centerX, viewRect.centerY - bufferRect.centerY)
        val matrix = new Matrix()
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = Math.max(textureView.width.toFloat / textureSize.getWidth, textureView.height.toFloat / textureSize.getHeight)
        matrix.postScale(scale, scale, viewRect.centerX, viewRect.centerY)
        matrix.postRotate((rotation + 2) * 90, viewRect.centerX, viewRect.centerY)
        matrix
      }
    }
  }}

  val saveDng = Var(true)
  val burstCaptureRawYuv = Var[RawOrYuv](Yuv)
  val burst = Var(1)
  val exposureBracketing = Var(false)

  val autoFocus = Var(true)
  val focusDistance = Var(0f)
  val focus = Rx[Focus] { if (autoFocus()) AutoFocus else new ManualFocus(focusDistance()) }

  val autoExposure = Var(true)
  val isos = Vector(40, 50, 80, 100, 200, 300, 400, 600, 800, 1000, 1600, 2000, 3200, 4000, 6400, 8000, 10000)
  val validIsos = Rx { lcamera().toList flatMap { camera => isos filter { camera.isoRange contains _ }} }
  val iso = Var(100)
  val exposureTimes = Vector[Double](1.2, 2, 4, 6, 8, 15, 30, 60, 100, 125, 250, 500, 750, 1000, 1500, 2000, 3000, 4000, 5000, 6000, 8000, 10000, 20000, 30000, 75000) map { d => (1000000000l / d).toLong }
  val validExposureTimes = Rx { lcamera().toList flatMap { camera => exposureTimes filter { camera.exposureTimeRange contains _ }} }
  val exposureTime = Var(1000000000l / 30)
  val exposure = Rx[Exposure] { if (autoExposure()) AutoExposure else new ManualExposure(exposureTime(), iso()) }

  observe { for { (focus, exposure, session) <- Rx { (focus(), exposure(), lcamera() flatMap { _.captureSession() flatMap { _.toOption } } ) } } {
    session foreach { _.setupPreview(focus, exposure) }
  } }

  observe { for { (previewSurface, session) <- Rx { ( previewSurface(), lcamera() flatMap { _.captureSession() } ) } } {
    previewSurface foreach { surface => if (session.isEmpty) { lcamera() foreach { _.openPhotoSession(surface) } } }
  } }

  val videoConfigurations = List(
    new VideoConfiguration(3840, 2160, 30, 65000000),
    new VideoConfiguration(3264, 2448, 30, 65000000),
    new VideoConfiguration(3264, 2448, 30, 35000000),
    new VideoConfiguration(1920, 1080, 60, 16000000),
    new VideoConfiguration(1920, 1080, 30, 8000000),
    new VideoConfiguration(1600, 1200, 60, 16000000),
    new VideoConfiguration(1600, 1200, 30, 8000000),
    new VideoConfiguration(1280, 720, 60, 10000000),
    new VideoConfiguration(1280, 720, 30, 5000000),
    new VideoConfiguration(800, 600, 120, 5000000))

  val availableVideoConfigurations = Rx { lcamera().toList flatMap { camera => videoConfigurations filter { vc =>
    val size = new Size(vc.width, vc.height)
    size <= camera.rawSize && camera.yuvSizes.contains(size) && vc.fps <= camera.rawFps
  } } }

  val userVideoConfiguration = Var(videoConfigurations(0))

  lazy val textureView = new STextureView {
    private[this] val surfaceTextureVar = NoneVar[SurfaceTexture]
    def surfaceTexture: Rx[Option[SurfaceTexture]] = surfaceTextureVar

    onTouch((v, e) => {
      if (e.getActionMasked == MotionEvent.ACTION_DOWN) {
        lcamera() foreach { camera =>
          camera.captureSession() flatMap { _.value flatMap { _.toOption } } foreach {
            case session =>
              if (autoFocus() || autoExposure()) {
                val meteringRectangleSize = 300
                val left = camera.activeArraySize.left
                val right = camera.activeArraySize.right
                val top = camera.activeArraySize.top
                val bottom = camera.activeArraySize.bottom

                val x = e.getX / v.getWidth
                val y = e.getY / v.getHeight
                val mr = new MeteringRectangle(
                  0 max (left + (right - left) * y - meteringRectangleSize / 2).round,
                  0 max (bottom - (bottom - top) * x - meteringRectangleSize / 2).round,
                  meteringRectangleSize, meteringRectangleSize, 1
                )

                session.triggerMetering(mr, focus(), exposure())
        } } }
        true
      } else false
    })

    setSurfaceTextureListener(new TextureView.SurfaceTextureListener {
      override def onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        debug(s"Surface texture available: $texture")
        surfaceTextureVar() = Option(texture)
      }

      override def onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = onSurfaceTextureAvailable _
      override def onSurfaceTextureUpdated(st: SurfaceTexture) {}
      override def onSurfaceTextureDestroyed(st: SurfaceTexture) = {
        debug("Surface texture destroyed")
        surfaceTextureVar() = None
        true
      }
    })
  }

  lazy val captureButton = new SImageButton {
    val photoStandbyColor = Color.parseColor("#4285f4")
    val capturingColor = Color.parseColor("#d0d0d0")
    val videoStandbyColor = Color.parseColor("#99cc00")
    val recordingColor = Color.parseColor("#ff4444")
    def fadeTo(color: Int, drawable: Int) {
      imageDrawable = drawable
      val anim = ObjectAnimator.ofArgb(this, "backgroundColor", background.asInstanceOf[ColorDrawable].getColor, color)
      anim.addListener(new AnimatorListenerAdapter() {
        override def onAnimationEnd(animator: Animator) { backgroundColor = color }
      })
      anim.setDuration(150)
      anim.start()
    }

    backgroundColor = photoStandbyColor
    scaleType = ScaleType.FIT_CENTER
    onClick { lcamera() foreach { camera => camera.captureSession() flatMap { _.toOption } match {
      case Some(ps: camera.PhotoSession) =>
        val time = new Time
        time.setToNow()
        val filePathBase = Utils.createPathIfNotExist(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/Camera/") + time.format("IMG_%Y%m%d_%H%M%S")
        val orientation = windowManager.getDefaultDisplay.getRotation

        ps.capture(focus(), exposure(), { (result, jpegImage, rawImage) => {
          val filePath = s"$filePathBase.jpg"
          val jpegBuffer = jpegImage.getPlanes()(0).getBuffer
          val bytes = new Array[Byte](jpegBuffer.capacity)
          jpegBuffer.get(bytes)
          new FileOutputStream(filePath).write(bytes)
          mediaScan(filePath, ACTION_NEW_PICTURE)
          debug(s"JPEG saved: $filePath")

          if (saveDng()) {
            saveDngFile(s"$filePathBase.dng", camera.characteristics, result, rawImage, orientation)
          }
        }})
      case Some(bs: camera.BurstSession) =>
        val time = new Time
        time.setToNow()
        val filePathBase = Utils.createPathIfNotExist(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/Camera/") + time.format("IMG_%Y%m%d_%H%M%S")
        val orientation = windowManager.getDefaultDisplay.getRotation

        val requests = for (n <- 1 to 7) yield bs.Request(focus(), exposure(),
          bs.rawYuv match {
            case Raw => (result, image) => saveDngFile(s"${filePathBase}_$n.dng", camera.characteristics, result, image, orientation)
            case Yuv => (result, image) => saveYuvAsJpeg(s"${filePathBase}_$n.jpg", image)// TODO
        })

        bs.burstCapture(requests.toList)
      case Some(bs: camera.BulbSession) => if (!bs.capturing()) {
        val time = new Time
        time.setToNow()
        val filePathBase = Utils.createPathIfNotExist(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/Camera/") + time.format("IMG_%Y%m%d_%H%M%S")
        val orientation = windowManager.getDefaultDisplay.getRotation

        bs.startCapturing(focus(), exposure(), (result, image, n) => saveDngFile(s"${filePathBase}_$n.dng", camera.characteristics, result, image, orientation))
      } else bs.stopCapturing()
      case Some(vs: camera.VideoSession) => if (!vs.recording()) vs.startRecording(focus(), exposure()) else vs.stopRecording()
      case _ =>
    }}}

    observe {
      Rx {
        lcamera() match {
          case Some(camera) => camera.captureSession() flatMap { _.toOption } match {
            case Some(ps: camera.PhotoSession) => if (ps.capturing()) (capturingColor, R.drawable.ic_camera, false) else (photoStandbyColor, R.drawable.ic_camera, true)
            case Some(bs: camera.BurstSession) => if (bs.capturing()) (capturingColor, R.drawable.ic_camera, false) else (photoStandbyColor, R.drawable.ic_camera, true)
            case Some(bs: camera.BulbSession) => if (bs.capturing()) (recordingColor, R.drawable. ic_av_stop, true) else (photoStandbyColor, R.drawable.ic_camera, true)
            case Some(vs: camera.VideoSession) => if (vs.recording()) (recordingColor, R.drawable.ic_av_stop, true) else (videoStandbyColor, R.drawable.ic_video, true)
            case _ => (capturingColor, R.drawable.ic_camera, false) // FIXME
          }
          case _ => (capturingColor, R.drawable.ic_camera, false) // FIXME
        }
      } foreach {
        case (color, icon, en) =>
          enabled = en
          fadeTo(color, icon)
      }
    }
  }

  lazy val toolbar = new SLinearLayout {
    clickable = true
    backgroundColor = Color.parseColor("#fafafa")
    gravity = Gravity.CENTER
    visibility = View.INVISIBLE
    enabled = false

    += (new STextView {
      text = "Focus"
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
      onClick {
        afView.enabled = true
        circularReveal(afView, this.left + this.getWidth / 2, this.top + this.getHeight / 2, afView.width).start()
      }
    }.padding(16.dip, 16.dip, 16.dip, 16.dip).wrap)

    += (new STextView {
      text = "Exposure"
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
      onClick {
        aeView.enabled = true
        circularReveal(aeView, this.left + this.getWidth / 2, this.top + this.getHeight / 2, aeView.width).start()
      }
    }.padding(16.dip, 16.dip, 16.dip, 16.dip).wrap)

    += (new STextView {
      text = "Burst"
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
      onClick {
        burstView.enabled = true
        circularReveal(burstView, this.left + this.getWidth / 2, this.top + this.getHeight / 2, aeView.width).start()
      }

      observe { Rx { lcamera() match {
        case Some(camera) => camera.captureSession() flatMap { _.toOption } match {
          case Some(_: camera.PhotoSession) => true
          case Some(_: camera.BurstSession) => true
          case Some(_: camera.BulbSession) => false
          case Some(_: camera.VideoSession) => false
          case None => false
        }
        case None => false
      } } foreach { enabled = _ } }
    }.padding(16.dip, 16.dip, 16.dip, 16.dip).wrap)

    += (new SImageView {
      backgroundResource = resolveAttr(android.R.attr.selectableItemBackground)
      observe { Rx { lcamera() match {
        case Some(camera) => camera.captureSession() flatMap { _.toOption } match {
          case Some(_: camera.PhotoSession | _: camera.BurstSession) => R.drawable.ic_bulb_black
          case Some(_: camera.BulbSession) => R.drawable.ic_video_black
          case Some(_: camera.VideoSession) => R.drawable.ic_camera_black
          case None => R.drawable.ic_video_black // FIXME
        }
        case None => R.drawable.ic_video_black // FIXME
      } } foreach { imageDrawable = _ } }
      observe { Rx { lcamera() match {
        case Some(camera) => camera.captureSession() flatMap { _.toOption } match {
          case Some(ps: camera.PhotoSession) => !ps.capturing()
          case Some(bs: camera.BurstSession) => !bs.capturing()
          case Some(bs: camera.BulbSession) => !bs.capturing()
          case Some(vs: camera.VideoSession) => !vs.recording()
          case None => true
        }
        case None => false
      } } foreach { enabled = _ } }
      onClick {
        for { camera <- lcamera() ; previewSurface <- previewSurface() } {
          camera.captureSession() flatMap { _.toOption } match {
            case Some(_: camera.PhotoSession | _: camera.BurstSession) => camera.openBulbSession(previewSurface)
            case Some(_: camera.BulbSession) => camera.openVideoSession(previewSurface, userVideoConfiguration())
            case Some(_: camera.VideoSession) => camera.openPhotoSession(previewSurface)
            case None => camera.openVideoSession(previewSurface, userVideoConfiguration())
          }
        }
      }
    }.<<(48.dip, 48.dip).marginLeft(16.dip).marginRight(16.dip).>>)

    += (new SImageView {
      imageDrawable = R.drawable.ic_settings

      onClick {
        new AlertDialogBuilder {
          setView(new SVerticalLayout {
            += (new SLinearLayout {
              +=(new STextView {
                text = "Video Resolution"
                typeface = Typeface.DEFAULT_BOLD
              }.wrap.<<.Gravity(Gravity.LEFT).Weight(1.0f).marginRight(16.dip).>>)

              +=(new STextView {
                observe { userVideoConfiguration foreach { vc => text = vc.toString } }
              }.wrap.<<.Gravity(Gravity.RIGHT).Weight(1.0f).marginLeft(16.dip).>>)

              onClick {
                new AlertDialogBuilder("Video Resolution") {
                  val videoConfigurationAdapter: ArrayAdapter[VideoConfiguration] = new SArrayAdapter(videoConfigurations.toArray) {
                    override def isEnabled(which: Int): Boolean = availableVideoConfigurations() contains videoConfigurations(which)

                    override def getView(which: Int, convertView: View, parent: ViewGroup): View =
                      new STextView {
                        text = videoConfigurations(which).toString
                        textColor = Color.parseColor { videoConfigurationAdapter.isEnabled(which) match {
                          case true => if (videoConfigurations(which) == userVideoConfiguration()) "#4285f4" else "#000000"
                          case false => "#d0d0d0"
                        }}
                        textSize = 16.sp
                      }.padding(16.dip)
                  }

                  setAdapter(videoConfigurationAdapter, new DialogInterface.OnClickListener {
                    override def onClick(dialog: DialogInterface, which: Int) { userVideoConfiguration() = videoConfigurations(which) }
                  })
                }.show()
              }
            }.padding(16.dip).fw)

            += (new SLinearLayout {
              +=(new STextView {
                text = "Save DNG"
                typeface = Typeface.DEFAULT_BOLD
              }.wrap.<<.Gravity(Gravity.LEFT).Weight(1.0f).marginRight(16.dip).>>)

              +=(new SSwitch {
                observe { saveDng foreach setChecked }
                onCheckedChanged { (v: View, checked: Boolean) => saveDng() = checked }
              }.wrap.<<.Gravity(Gravity.RIGHT).Weight(1.0f).marginLeft(16.dip).>>)
            }.padding(16.dip).fw)
          }.fill)
        }.show()
      }
    }.<<(48.dip, 48.dip).marginLeft(16.dip).marginRight(16.dip).>>)
  }

  lazy val afView = new SLinearLayout {
    clickable = true
    backgroundColor = Color.parseColor("#fafafa")
    gravity = Gravity.CENTER
    visibility = View.INVISIBLE
    enabled = false

    += (new STextView {
      text = "Auto Focus"
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).wrap)
    += (new SSwitch {
      observe { autoFocus.foreach(setChecked) }
      onCheckedChanged { (v: View, checked: Boolean) => autoFocus() = checked }
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).wrap)
    += (new SSeekBar {
      observe { lcamera foreach { _ foreach { camera => max = (camera.minFocusDistance * 100).round } } }
      observe { autoFocus foreach { af => enabled = !af } }
      observe { focusDistance.foreach { fd => setProgress { (fd * 100).round } } }
      onProgressChanged { (seekbar: SeekBar, value: Int, fromUser: Boolean) => {
        if (fromUser)
          focusDistance() = value.toFloat / 100
      }}
    }.padding(8.dip, 8.dip, 8.dip, 8.dip).fw)
  }.padding(16.dip, 0, 16.dip, 0)

  lazy val aeView = new SLinearLayout {
    clickable = true
    backgroundColor = Color.parseColor("#fafafa")
    gravity = Gravity.CENTER
    visibility = View.INVISIBLE
    enabled = false

    += (new STextView {
      text = "Auto Exposure"
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).wrap)

    += (new SSwitch {
      observe { autoExposure foreach setChecked }
      onCheckedChanged { (v: CompoundButton, checked: Boolean) => autoExposure() = checked }
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).wrap)

    sealed trait PrevNext
    case object Prev extends PrevNext
    case object Next extends PrevNext
    def mkButton(pv: PrevNext, f: => Unit) = new SImageView {
      backgroundResource = resolveAttr(android.R.attr.selectableItemBackground)
      observe { autoExposure.foreach { ae =>
        enabled = !ae
        imageDrawable = (pv, ae) match {
          case (Prev, false) => R.drawable.ic_navigation_previous_item
          case (Prev, true) => R.drawable.ic_navigation_previous_item_disabled
          case (Next, false) => R.drawable.ic_navigation_next_item
          case (Next, true) => R.drawable.ic_navigation_next_item_disabled
        }
      }}

      onClick(f)
    }

    += (mkButton(Prev, { (validExposureTimes() filter { _ > exposureTime() }).lastOption foreach { exposureTime() = _ } }).<<(32.dip, 32.dip).>>)
    += (new STextView {
      observe { exposureTime foreach { t => text = if (t >= 500000000) f"${t / 1000000000.0}%.1f″" else s"1/${1000000000 / t}" } }
      observe { autoExposure foreach { ae => textColor = if (ae) Color.parseColor("#d0d0d0") else Color.parseColor("#000000") } }
    }.padding(4.dip, 16.dip, 4.dip, 16.dip).wrap)
    += (mkButton(Next, { validExposureTimes() find { _ < exposureTime() } foreach { exposureTime() = _ } }).<<(32.dip, 32.dip).>>)

    += (mkButton(Prev, { (validIsos() filter { _ < iso() }).lastOption foreach { iso() = _ } }).<<(32.dip, 32.dip).>>)
    += (new STextView {
      observe { iso foreach { v => text = s"ISO $v" } }
      observe { autoExposure foreach { ae => textColor = Color.parseColor { if (ae) "#d0d0d0" else "#000000" } } }
    }.padding(4.dip, 16.dip, 4.dip, 16.dip).wrap)
    += (mkButton(Next, { validIsos() find { _ > iso() } foreach { iso() = _ } }).<<(32.dip, 32.dip).>>)
  }

  lazy val burstView = new SLinearLayout {
    clickable = true
    backgroundColor = Color.parseColor("#fafafa")
    gravity = Gravity.CENTER
    visibility = View.INVISIBLE
    enabled = false

    += (new STextView {
      text = "Burst"
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).wrap)

    val isBurstSession = Rx { lcamera() exists { camera => camera.captureSession() flatMap { _.toOption } exists { _.isInstanceOf[camera.BurstSession] } } }
    += (new SSwitch {
      observe { isBurstSession foreach { checked = _ } }
      onCheckedChanged { (v: CompoundButton, checked: Boolean) => for { camera <- lcamera() ; surface <- previewSurface() } {
        if (checked) { if (!isBurstSession()) camera.openBurstSession(surface, burstCaptureRawYuv()) } else camera.openPhotoSession(surface)
      }}
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).wrap)

    += (new SCheckBox {
      text = "Exposure Bracket"
      observe { exposureBracketing foreach { setChecked } }
      observe { isBurstSession foreach { enabled = _ } }
      onCheckedChanged { (v: View, checked: Boolean) => exposureBracketing() = checked }
    })

    += (new STextView {
      observe { burstCaptureRawYuv foreach {
        case Raw => text = "DNG"
        case Yuv => text = "JPEG"
      }}
      observe { isBurstSession foreach {
        en => enabled = en
        textColor = Color.parseColor(if (en) "#4285f4" else "#d0d0d0")
      }}
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
      onClick {
        burstCaptureRawYuv() = burstCaptureRawYuv() match { case Raw => Yuv ; case Yuv => Raw }
        for { camera <- lcamera() ; surface <- previewSurface() } {
          if (isBurstSession()) camera.openBurstSession(surface, burstCaptureRawYuv())
        }}
    }.padding(16.dip, 16.dip, 8.dip, 16.dip).wrap)
  }

  lazy val fabSize = 40.dip
  lazy val fabMargin = 16.dip
  lazy val fab = new Fab(ctx) with TraitImageButton[Fab] {
    val basis = this

    setType(FloatingActionButton.TYPE_MINI)
    setShadow(false)
    setColorNormal(Color.parseColor("#ff4081"))
    setColorPressed(Color.parseColor("#ff80ab"))
    imageResource = R.drawable.ic_core_overflow_rotated

    onClick {
      val animatorSet = new AnimatorSet()
      animatorSet.play(circularHide(basis, fabSize / 2, fabSize / 2, fabSize / 2))
                 .before(circularReveal(toolbar, fabMargin + fabSize / 2, toolbar.height - fabMargin - fabSize / 2, toolbar.width))
      animatorSet.start()
      enabled = false
      toolbar.enabled = true
    }
  }

  lazy val progressBar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal) with TraitProgressBar[ProgressBar] {
    val basis = this
    indeterminate = true
    observe { Rx {
      lcamera() match {
        case Some(camera) => camera.captureSession() match {
          case Some(ps: camera.PhotoSession) => ps.capturing()
          case _ => false
        }
        case _ => false
      }
    } foreach { c => visibility = if (c) View.VISIBLE else View.INVISIBLE } }
  }

  lazy val orientationEventListener = new OrientationEventListener(this) {
    override def onOrientationChanged(ignored: Int) = {
      val newOrientation = windowManager.getDefaultDisplay.getRotation
      if (orientation() != newOrientation) {
        orientation() = newOrientation
      }
    }
  }

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)

    if (List(0, 2) contains windowManager.getDefaultDisplay.getRotation) {
      finish()
      return
    }

    if (Log.isLoggable("lcamera", Log.VERBOSE)) {
      dumpCameraInfo()
    }

    contentView = new SRelativeLayout {
      += (textureView.<<.alignParentLeft.alignParentTop.alignParentBottom.leftOf(captureButton).>>)
      += (toolbar.<<.fw.alignParentLeft.leftOf(captureButton).alignParentBottom.>>)
      += (afView.<<.fw.alignParentLeft.leftOf(captureButton).alignParentBottom.>>)
      += (aeView.<<.fw.alignParentLeft.leftOf(captureButton).alignParentBottom.>>)
      += (burstView.<<.fw.alignParentLeft.leftOf(captureButton).alignParentBottom.>>)
      += (captureButton.<<(96.dip, MATCH_PARENT).alignParentRight.alignParentTop.alignParentBottom.>>)
      += (progressBar.<<.fw.alignParentLeft.leftOf(captureButton).alignParentBottom.marginBottom(-4.dip).>>)
      += (fab.<<.wrap.alignParentLeft.alignParentBottom.marginLeft(fabMargin).marginBottom(fabMargin).>>)
    }

    getWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    val prefs = new Preferences(getSharedPreferences("lcamera", Context.MODE_PRIVATE))
    prefs.Boolean.autoFocus.foreach { autoFocus() = _ }
    prefs.Float.focusDistance.foreach { focusDistance() = _ }
    prefs.Boolean.autoExposure.foreach { autoExposure() = _ }
    prefs.Int.iso.foreach { iso() = _ }
    prefs.Long.exposureTime.foreach { exposureTime() = _ }
    prefs.Int.burst.foreach { burst() = _ }
    prefs.Boolean.exposureBracketing.foreach { exposureBracketing() = _ }
    for { vcWidth <- prefs.Int.vcWidth
          vcHeight <- prefs.Int.vcHeight
          vcFps <- prefs.Int.vcFps
          vcBitrate <- prefs.Int.vcBitrate } {
      val vc = new VideoConfiguration(vcWidth, vcHeight, vcFps, vcBitrate)
      if (videoConfigurations contains vc)
        userVideoConfiguration() = vc
    }
    prefs.Boolean.saveDng.foreach { saveDng() = _ }
    prefs.Boolean.burstCaptureRawYuv.foreach { b => burstCaptureRawYuv() = if (b) Raw else Yuv }
  }

  observe { for { cameraOpt <- lcamera ; camera <- cameraOpt } {
    val capabilities = camera.characteristics.get(REQUEST_AVAILABLE_CAPABILITIES)
    val requiredCapabilities = List(
      REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
      REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING,
      REQUEST_AVAILABLE_CAPABILITIES_RAW)

    if (!(requiredCapabilities forall { capabilities contains _ })) {
      toast("L Camera is not supported on your device")
      finish()
    }
  }}

  override def onResume() {
    super.onResume()

    val lcameraManager = new LCameraManager
    observe { lcameraManager.openLCamera("0") foreach { lcamera() = _ }}
    orientation() = windowManager.getDefaultDisplay.getRotation
    orientationEventListener.enable()
  }

  override def onPause() {
    super.onPause()
    lcamera() foreach { _.close() }
    orientationEventListener.disable()
  }

  override def onStop() {
    super.onStop()

    val prefs = new Preferences(getSharedPreferences("lcamera", Context.MODE_PRIVATE))
    prefs.autoFocus = autoFocus()
    prefs.focusDistance = focusDistance()
    prefs.autoExposure = autoExposure()
    prefs.iso = iso()
    prefs.exposureTime = exposureTime()
    prefs.burst = burst()
    prefs.exposureBracketing = exposureBracketing()
    prefs.vcWidth = userVideoConfiguration().width
    prefs.vcHeight = userVideoConfiguration().height
    prefs.vcFps = userVideoConfiguration().fps
    prefs.vcBitrate = userVideoConfiguration().bitrate
    prefs.saveDng = saveDng()
    prefs.burstCaptureRawYuv = burstCaptureRawYuv() match { case Raw => true ; case Yuv => false }
  }

  override def onBackPressed() {
    if (afView.enabled) {
      slideDownHide(afView)
      afView.enabled = false
    } else if (aeView.enabled) {
      slideDownHide(aeView)
      aeView.enabled = false
    } else if (burstView.enabled) {
      slideDownHide(burstView)
      burstView.enabled = false
    } else if (toolbar.enabled) {
      val animatorSet = new AnimatorSet()
      animatorSet.play(circularHide(toolbar, fabMargin + fabSize / 2, toolbar.height - fabMargin - fabSize / 2, toolbar.width))
                 .before(circularReveal(fab, fabSize / 2, fabSize / 2, fabSize / 2))
      animatorSet.start()
      fab.enabled = true
      toolbar.enabled = false
    } else {
      super.onBackPressed()
    }
  }

  override def onKeyDown(keyCode: Int, event: KeyEvent): Boolean = {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
      captureButton.performClick()
      true
    } else {
      super.onKeyDown(keyCode, event)
    }
  }

  def dumpCameraInfo() {
    for { cameraId <- cameraManager.getCameraIdList } {
      verbose(s"===== Camera $cameraId =====")
      val characteristics = cameraManager.getCameraCharacteristics(cameraId)
      for { key <- characteristics.getKeys } {
        verbose(key.getName)
        characteristics.get(key) match {
          case scm: StreamConfigurationMap =>
            scm.getOutputFormats foreach { format =>
              verbose(s"Format ${format.toString}")
              Option(scm.getOutputSizes(format)) foreach { _ foreach { size =>
                verbose(s"$size: ${scm.getOutputMinFrameDuration(format, size)} ${scm.getOutputStallDuration(format, size)}")
              }}
            }
          case xs: Array[_] => xs foreach { x => verbose(x.toString) }
          case x => verbose(x.toString)
        }
      }
    }
  }

  def saveDngFile(filePath: String, characteristics: CameraCharacteristics, result: TotalCaptureResult, image: Image, orientation: Int) {
    val dngCreator = new DngCreator(characteristics, result).setOrientation(orientation)
    dngCreator.writeImage(new FileOutputStream(filePath), image)
    dngCreator.close()
    mediaScan(filePath, ACTION_NEW_PICTURE)
    debug(s"DNG saved: $filePath")
  }

  def saveYuvAsJpeg(filePath: String, image: Image) {
    val bytes = List(0, 2) map { n => byteBufferToByteArray(image.getPlanes()(n).getBuffer) } reduceLeft { _ ++ _ }
    val yuvImage = new YuvImage(bytes, ImageFormat.NV21, image.getWidth, image.getHeight, null)

    val outputStream = new FileOutputStream(filePath)
    val rect = new Rect(0, 0, image.getWidth, image.getHeight)
    debug(s"${rect.width} ${rect.height}")
    yuvImage.compressToJpeg(rect, 100, outputStream)
    image.close()
    outputStream.close()
    mediaScan(filePath, ACTION_NEW_PICTURE)
    debug(s"JPEG saved: $filePath")
  }

  def resolveAttr(attr: Int): Int = {
    val ta = obtainStyledAttributes(Array[Int](attr))
    val resId = ta.getResourceId(0, 0)
    ta.recycle()
    resId
  }
}
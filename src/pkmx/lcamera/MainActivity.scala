package pkmx.lcamera

import collection.JavaConversions._
import java.io.{File, FileOutputStream}
import java.nio.ByteBuffer
import java.text.DecimalFormat
import java.util.concurrent.Semaphore
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Channel, ExecutionContext, Future}
import scala.collection.immutable.Vector
import scala.language.{existentials, implicitConversions}
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

  class MyMediaRecorder(vc: VideoConfiguration, orientation: Int) extends MediaRecorder {
    import MyMediaRecorder._
    var realFilePath = tmpFilePath

    setVideoSource(2) // SURFACE
    setAudioSource(1) // MIC
    setOutputFormat(2) // MPEG_4
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

  sealed trait Observable {
    var obses: List[Obs] = List()

    def observe(obs: Obs): Obs = {
      obses = obs :: obses
      obs
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
}

import Utils._

class MainActivity extends SActivity with Observable {
  override implicit val loggerTag = LoggerTag("lcamera")
  lazy val cameraManager = getSystemService(Context.CAMERA_SERVICE).asInstanceOf[CameraManager]
  lazy val cameraId = cameraManager.getCameraIdList()(0)
  lazy val characteristics = cameraManager.getCameraCharacteristics(cameraId)
  lazy val streamConfigurationMap = characteristics.get(SCALER_STREAM_CONFIGURATION_MAP)
  lazy val activeArraySize = characteristics.get(SENSOR_INFO_ACTIVE_ARRAY_SIZE)
  lazy val minFocusDistance = characteristics.get(LENS_INFO_MINIMUM_FOCUS_DISTANCE)
  lazy val isoRange = characteristics.get(SENSOR_INFO_SENSITIVITY_RANGE)
  lazy val exposureTimeRange = characteristics.get(SENSOR_INFO_EXPOSURE_TIME_RANGE)

  lazy val yuvSizes = streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)
  lazy val jpegSize = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG).filter(_ <= rawSize)(0)
  lazy val rawSize = streamConfigurationMap.getOutputSizes(ImageFormat.RAW_SENSOR)(0)
  lazy val yuvSize = yuvSizes.filter(_ <= rawSize)(0)
  lazy val minFrameDuration = streamConfigurationMap.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, rawSize)
  lazy val rawFps = if (minFrameDuration != 0) 1000000000 / minFrameDuration else Int.MaxValue
  lazy val jpegImageReader = ImageReader.newInstance(jpegSize.getWidth, jpegSize.getHeight, ImageFormat.JPEG, 1)
  lazy val rawImageReader = ImageReader.newInstance(rawSize.getWidth, rawSize.getHeight, ImageFormat.RAW_SENSOR, 7)
  lazy val yuvImageReader = ImageReader.newInstance(yuvSize.getWidth, yuvSize.getHeight, ImageFormat.YUV_420_888, 7)
  lazy val jpegSurface = jpegImageReader.getSurface
  lazy val rawSurface = rawImageReader.getSurface
  lazy val yuvSurface = yuvImageReader.getSurface
  val jpegImages = new Channel[Image]
  val rawImages = new Channel[Image]
  val yuvImages = new Channel[Image]
  val saveDng = Var(true)

  sealed trait DngJpeg
  case object Dng extends DngJpeg
  case object Jpeg extends DngJpeg
  val burstSaveDngJpeg = Var[DngJpeg](Jpeg)

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
  lazy val availableVideoConfigurations = videoConfigurations filter { vc =>
    val size = new Size(vc.width, vc.height)
    size <= rawSize && yuvSizes.contains(size) && vc.fps <= rawFps
  }
  lazy val userVideoConfiguration = Var(videoConfigurations(0))
  lazy val videoConfiguration = Rx { if (availableVideoConfigurations contains userVideoConfiguration()) userVideoConfiguration() else availableVideoConfigurations(0) }

  val camera = NoneVar[CameraDevice]
  val previewSurface = NoneVar[Surface]
  val previewSession = NoneVar[CameraCaptureSession]
  val createCaptureSessionSemaphore = new Semaphore(1)
  val meteringRectangle = NoneVar[MeteringRectangle]

  sealed trait CaptureMode

  class PhotoMode extends CaptureMode {
    val capturing = Var(false)

    def capture() {
      for { camera <- MainActivity.this.camera()
            previewSession <- MainActivity.this.previewSession()
            previewSurface <- MainActivity.this.previewSurface()
      } {
        debug(s"Starting capture using $camera")
        capturing() = true

        val time = new Time
        time.setToNow()
        val filePathBase = Utils.createPathIfNotExist(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/Camera/") + time.format("IMG_%Y%m%d_%H%M%S")
        val orientation = windowManager.getDefaultDisplay.getRotation
        val targetSurfaces =
          if (burst() > 1)
            burstSaveDngJpeg() match {
              case Dng => List(rawSurface)
              case Jpeg => List(yuvSurface)
            }
          else previewSurface :: (if (saveDng()) List(jpegSurface, rawSurface) else List(jpegSurface))

        val requests = for (n <- 0 to burst() - 1) yield {
          val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

          request.set(CONTROL_MODE, CONTROL_MODE_AUTO)

          if (burst() > 1 && focusStacking()) {
            request.set(CONTROL_AF_MODE, CONTROL_AF_MODE_OFF)
            request.set(LENS_FOCUS_DISTANCE, minFocusDistance * (n.toFloat / (burst() - 1)))
          } else if (autoFocus()) {
            request.set(CONTROL_AF_MODE, CONTROL_AF_MODE_AUTO)
          } else {
            request.set(CONTROL_AF_MODE, CONTROL_AF_MODE_OFF)
            request.set(LENS_FOCUS_DISTANCE, focusDistance())
          }

          val bracketMap = Vector[Double](8, 4, 2, 1, 0.5, 0.25, 0.125)
          request.set(CONTROL_AE_MODE, CONTROL_AE_MODE_OFF)
          request.set(SENSOR_SENSITIVITY, if (autoExposure()) autoIso() else iso())
          if (burst() > 1 && exposureBracketing()) {
            request.set(SENSOR_EXPOSURE_TIME, ((if (autoExposure()) autoExposureTime() else exposureTime()) * bracketMap(n)).round)
          } else {
            request.set(SENSOR_EXPOSURE_TIME, if (autoExposure()) autoExposureTime() else exposureTime())
          }

          request.set(LENS_OPTICAL_STABILIZATION_MODE, LENS_OPTICAL_STABILIZATION_MODE_ON)

          if (targetSurfaces contains jpegSurface) {
            request.set(JPEG_QUALITY, 100.toByte)
            request.set(JPEG_ORIENTATION, orientationToDegree(orientation))
          }

          if (targetSurfaces contains rawSurface) {
            request.set(STATISTICS_LENS_SHADING_MAP_MODE, STATISTICS_LENS_SHADING_MAP_MODE_ON) // Required for RAW capture
          }

          targetSurfaces map request.addTarget
          request.build()
        }

        val tasks = new ListBuffer[Future[Unit]]
        val rawResults = new Channel[TotalCaptureResult]

        debug(s"Capturing with $previewSession")
        previewSession.captureBurst(requests, new CameraCaptureSession.CaptureCallback {
          override def onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            debug(s"Capture completed: " +
              s"focus = ${result.get(CaptureResult.LENS_FOCUS_DISTANCE)}/${request.get(LENS_FOCUS_DISTANCE)} " +
              s"iso = ${result.get(CaptureResult.SENSOR_SENSITIVITY)}/${request.get(SENSOR_SENSITIVITY)} " +
              s"exposure = ${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)}/${request.get(SENSOR_EXPOSURE_TIME)}")

            if (targetSurfaces.contains(rawSurface)) { rawResults.write(result) }
          }

          override def onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            if (targetSurfaces.contains(jpegSurface)) {
              tasks += Future {
                val image = jpegImages.read
                val filePath = s"$filePathBase.jpg"
                val jpegBuffer = image.getPlanes()(0).getBuffer
                val bytes = new Array[Byte](jpegBuffer.capacity)
                jpegBuffer.get(bytes)
                image.close()
                new FileOutputStream(filePath).write(bytes)
                mediaScan(filePath, ACTION_NEW_PICTURE)
                debug(s"JPEG saved: $filePath")
              }
            }

            if (targetSurfaces.contains(rawSurface)) {
              tasks += Future {
                for (n <- 1 to burst()) {
                  val image = rawImages.read
                  val result = rawResults.read

                  val dngCreator = new DngCreator(characteristics, result).setOrientation(orientation)
                  val filePath = if (burst() > 1) s"${filePathBase}_$n.dng" else s"$filePathBase.dng"
                  dngCreator.writeImage(new FileOutputStream(filePath), image)
                  dngCreator.close()
                  image.close()
                  mediaScan(filePath, ACTION_NEW_PICTURE)
                  debug(s"DNG saved: $filePath")
                }
              }
            }

            if (targetSurfaces.contains(yuvSurface)) {
              tasks += Future {
                for (n <- 1 to burst()) {
                  val image: Image = yuvImages.read
                  val bytes = List(0, 2) map { n => byteBufferToByteArray(image.getPlanes()(n).getBuffer) } reduceLeft { _ ++ _ }
                  val yuvImage = new YuvImage(bytes, ImageFormat.NV21, image.getWidth, image.getHeight, null)

                  val filePath = s"${filePathBase}_$n.jpg"
                  val outputStream = new FileOutputStream(filePath)
                  val rect = new Rect(0, 0, image.getWidth, image.getHeight)
                  debug(s"${rect.width} ${rect.height}")
                  yuvImage.compressToJpeg(rect, 100, outputStream)
                  image.close()
                  outputStream.close()
                  mediaScan(filePath, ACTION_NEW_PICTURE)
                  debug(s"JPEG saved: $filePath")
                }
              }
            }

            tasks foreach { _ onFailure { case NonFatal(e) => e.printStackTrace() } }
            tasks reduce { (_: Future[Any]) zip (_: Future[Any]) } onComplete { _ => runOnUiThread { capturing() = false } }
          }

          override def onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            debug("Capture failed")
            capturing() = false
          }
        }, null)
      }
    }
  }

  class VideoMode extends CaptureMode {
    private val mediaRecorder = NoneVar[MyMediaRecorder]
    prepareRecorder()
    val videoSurface = Rx { mediaRecorder() map { _.getSurface } }
    val recording = Var(false)

    def prepareRecorder() { mediaRecorder() = Option(new MyMediaRecorder(videoConfiguration(), windowManager.getDefaultDisplay.getRotation)) }

    def releaseRecorder() {
      mediaRecorder() foreach { mr =>
        mr.reset()
        mr.release()
        mediaRecorder() = None
      }
    }

    def startRecording() {
      debug("Starting recording")
      mediaRecorder() foreach { mr =>
        val time = new Time
        time.setToNow()
        mr.realFilePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/Camera/" + time.format("VID_%Y%m%d_%H%M%S.mp4")
        mr.start()
        recording() = true
      }
    }

    def stopRecording() {
      debug("Stop recording")
      mediaRecorder() foreach { mr =>
        try {
          mr.stop()
          renameFile(MyMediaRecorder.tmpFilePath, mr.realFilePath)
          mediaScan(mr.realFilePath, ACTION_NEW_VIDEO)
        } catch {
          case e: RuntimeException => new File(MyMediaRecorder.tmpFilePath).delete()
        }
      }
      recording() = false
      releaseRecorder()
      prepareRecorder()
    }
  }

  val captureMode = Var[CaptureMode](new PhotoMode)
  val burst = Var(1)
  val focusStacking = Var(false)
  val exposureBracketing = Var(false)

  val autoFocus = Var(true)
  val focusDistance = Var(0f)
  val autoExposure = Var(true)
  val isoMap = Vector(40, 50, 80, 100, 200, 300, 400, 600, 800, 1000, 1600, 2000, 3200, 4000, 6400, 8000, 10000)
  val isoIndex = Var(3) // ISO 100
  val iso = isoIndex.map(isoMap)
  val autoIso = Var(100)
  val exposureTimeMap = Vector[Double](1.2, 2, 4, 6, 8, 15, 30, 60, 100, 125, 250, 500, 750, 1000, 1500, 2000, 3000, 4000, 5000, 6000, 8000, 10000, 20000, 30000, 75000)
  val exposureTimeIndex = Var(7) // 1/60s
  val exposureTime = Rx { (1000000000.0 / exposureTimeMap(exposureTimeIndex())).toLong }
  val autoExposureTime = Var(1000000000l)
  val metering = Var(false)

  lazy val textureView = new STextureView {
    onTouch((v, e) => {
      if (e.getActionMasked == MotionEvent.ACTION_DOWN) {
        if (autoFocus() || autoExposure()) {
          setMeteringRectangle(v, e)
        }
        true
      }
      else false
    })

    setSurfaceTextureListener(new TextureView.SurfaceTextureListener {
      override def onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val textureSize = streamConfigurationMap.getOutputSizes(texture.getClass).filter(sz => sz <= rawSize && sz <= new Size(1920, 1080))(0)
        texture.setDefaultBufferSize(textureSize.getWidth, textureSize.getHeight)
        debug(s"Surface texture available: $texture")
        previewSurface() = Option(new Surface(texture))

        setPreviewTransform(windowManager.getDefaultDisplay.getRotation)
      }

      override def onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = onSurfaceTextureAvailable _
      override def onSurfaceTextureUpdated(st: SurfaceTexture) {}
      override def onSurfaceTextureDestroyed(st: SurfaceTexture) = {
        debug("Surface texture destroyed")
        previewSurface() = None
        true
      }
    })
  }

  val setPreviewTransform: Int => Unit = rotation => {
    if (textureView.isAvailable) {
      textureView.setTransform {
        val textureSize = streamConfigurationMap.getOutputSizes(textureView.getSurfaceTexture.getClass).filter(sz => sz <= rawSize && sz <= new Size(1920, 1080))(0)
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
    onClick { captureMode() match {
      case pm: PhotoMode => pm.capture()
      case vm: VideoMode => if (!vm.recording()) vm.startRecording() else vm.stopRecording()
    }}

    observe {
      Rx {
        captureMode() match {
          case pm: PhotoMode if pm.capturing() => (capturingColor, R.drawable.ic_camera, false)
          case pm: PhotoMode if !pm.capturing() => (photoStandbyColor, R.drawable.ic_camera, true)
          case vm: VideoMode if vm.recording() => (recordingColor, R.drawable.ic_av_stop, true)
          case vm: VideoMode if !vm.recording() => (videoStandbyColor, R.drawable.ic_video, true)
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

      observe { Rx { captureMode().isInstanceOf[PhotoMode] } foreach { en =>
        enabled = en
        textColor = if (en) Color.parseColor("#737373") else Color.parseColor("#d0d0d0")
      }}
    }.padding(16.dip, 16.dip, 16.dip, 16.dip).wrap)

    += (new SImageView {
      observe { captureMode foreach {
        m => imageDrawable = m match {
          case _: PhotoMode => R.drawable.ic_video_black
          case _: VideoMode => R.drawable.ic_camera_black
      }}}
      val rxEnabled = Rx {
        captureMode() match {
          case pm: PhotoMode => !pm.capturing()
          case vm: VideoMode => !vm.recording()
        }
      }
      observe { rxEnabled foreach { enabled = _ } }
      backgroundResource = resolveAttr(android.R.attr.selectableItemBackground)
      onClick {
        captureMode() = captureMode() match {
          case _: PhotoMode => new VideoMode
          case vm: VideoMode => vm.releaseRecorder() ; new PhotoMode
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
                observe { videoConfiguration foreach { vc => text = vc.toString } }
              }.wrap.<<.Gravity(Gravity.RIGHT).Weight(1.0f).marginLeft(16.dip).>>)

              onClick {
                new AlertDialogBuilder("Video Resolution") {
                  val videoConfigurationAdapter: ArrayAdapter[VideoConfiguration] = new SArrayAdapter(videoConfigurations.toArray) {
                    override def isEnabled(which: Int): Boolean = availableVideoConfigurations contains videoConfigurations(which)

                    override def getView(which: Int, convertView: View, parent: ViewGroup): View =
                      new STextView {
                        text = videoConfigurations(which).toString
                        textColor = Color.parseColor { videoConfigurationAdapter.isEnabled(which) match {
                          case true => if (videoConfigurations(which) == videoConfiguration()) "#4285f4" else "#000000"
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
      max = (minFocusDistance * 100).round
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

    += (mkButton(Prev, { exposureTimeIndex() = Math.max(exposureTimeIndex() - 1, 0) }).<<(32.dip, 32.dip).>>)
    += (new STextView {
      observe { exposureTimeIndex foreach { v => text = s"1/${new DecimalFormat("#.#").format(exposureTimeMap(v))}" } }
      observe { autoExposure foreach { ae => textColor = if (ae) Color.parseColor("#d0d0d0") else Color.parseColor("#000000") } }
    }.padding(4.dip, 16.dip, 4.dip, 16.dip).wrap)
    += (mkButton(Next, { exposureTimeIndex() = Math.min(exposureTimeMap.length - 1, exposureTimeIndex() + 1) }).<<(32.dip, 32.dip).>>)

    += (mkButton(Prev, { isoIndex() = Math.max(isoIndex() - 1, 0) }).<<(32.dip, 32.dip).>>)
    += (new STextView {
      observe { iso foreach { v => text = s"ISO $v" } }
      observe { autoExposure foreach { ae => textColor = Color.parseColor { if (ae) "#d0d0d0" else "#000000" } } }
    }.padding(4.dip, 16.dip, 4.dip, 16.dip).wrap)
    += (mkButton(Next, { isoIndex() = Math.min(isoMap.length - 1, isoIndex() + 1) }).<<(32.dip, 32.dip).>>)
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

    += (new SSwitch {
      observe { burst foreach { n => checked = n > 1 } }
      onCheckedChanged { (v: CompoundButton, checked: Boolean) => burst() = if (checked) 7 else 1 }
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).wrap)

    += (new SCheckBox {
      text = "Focus Stack"
      observe { focusStacking foreach { setChecked } }
      observe { burst foreach { n => enabled = n > 1} }
      onCheckedChanged { (v: View, checked: Boolean) => focusStacking() = checked }
    })

    += (new SCheckBox {
      text = "Exposure Bracket"
      observe { exposureBracketing foreach { setChecked } }
      observe { burst foreach { n => enabled = n > 1} }
      onCheckedChanged { (v: View, checked: Boolean) => exposureBracketing() = checked }
    })

    += (new STextView {
      observe { burstSaveDngJpeg foreach {
        case Dng => text = "DNG"
        case Jpeg => text = "JPEG"
      }}
      observe { Rx { burst() > 1 } foreach {
        en => enabled = en
        textColor = Color.parseColor(if (en) "#4285f4" else "#d0d0d0")
      }}
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
      onClick { burstSaveDngJpeg() = burstSaveDngJpeg() match { case Dng => Jpeg ; case Jpeg => Dng } }
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
    val visibilityRx = Rx { captureMode() match {
      case pm: PhotoMode => pm.capturing()
      case _ => false
    }}
    observe { visibilityRx foreach { c => visibility = if (c) View.VISIBLE else View.INVISIBLE } }
  }

  val startPreview =
    for { (cameraOpt, previewSurfaceOpt, previewSessionOpt, autoFocus, focusDistance, autoExposure, iso, exposureTime, metering)
          <- Rx {(this.camera(), this.previewSurface(), this.previewSession(),
                  this.autoFocus(), this.focusDistance(),
                  this.autoExposure(), this.iso(), this.exposureTime(), this.metering())}
           camera <- cameraOpt
           previewSurface <- previewSurfaceOpt
           previewSession <- previewSessionOpt
    } {
      debug(s"Starting preview using $previewSession")
      val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      request.set(CONTROL_MODE, CONTROL_MODE_AUTO)
      if (autoFocus) {
        request.set(CONTROL_AF_MODE, CONTROL_AF_MODE_AUTO)
      } else {
        request.set(CONTROL_AF_MODE, CONTROL_AF_MODE_OFF)
        request.set(LENS_FOCUS_DISTANCE, focusDistance)
      }

      if (autoExposure) {
        request.set(CONTROL_AE_MODE, CONTROL_AE_MODE_ON)
        request.set(CONTROL_AE_LOCK, !metering)
      } else {
        request.set(CONTROL_AE_MODE, CONTROL_AE_MODE_OFF)
        request.set(SENSOR_SENSITIVITY, iso)
        request.set(SENSOR_EXPOSURE_TIME, exposureTime)
      }
      request.set(SENSOR_FRAME_DURATION, minFrameDuration)
      request.addTarget(previewSurface)
      captureMode() match {
        case vm: VideoMode => request.addTarget(vm.videoSurface().get)
        case _ =>
      }

      previewSession.setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback {
        override def onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) = {
          if (Vector(CONTROL_AF_STATE_FOCUSED_LOCKED, CONTROL_AF_STATE_NOT_FOCUSED_LOCKED).contains(result.get(CaptureResult.CONTROL_AF_STATE)))
            MainActivity.this.focusDistance() = result.get(CaptureResult.LENS_FOCUS_DISTANCE)

          if (Vector(CONTROL_AE_STATE_CONVERGED, CONTROL_AE_STATE_FLASH_REQUIRED).contains(result.get(CaptureResult.CONTROL_AE_STATE))) {
            MainActivity.this.autoExposureTime() = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            MainActivity.this.autoIso() = result.get(CaptureResult.SENSOR_SENSITIVITY)
            MainActivity.this.metering() = false
          }
        }
      }, null)
    }

  observe {
    val rxSurfaces = Rx {
      previewSurface() flatMap { previewSurface =>
        captureMode() match {
          case _: PhotoMode if burst() > 1 && burstSaveDngJpeg() == Jpeg => Some(List(previewSurface, yuvSurface))
          case _: PhotoMode => Some(List(previewSurface, jpegSurface, rawSurface))
          case vm: VideoMode => vm.videoSurface() map { List(previewSurface, _) }
        }
      }
    }

    for { (cameraOpt, surfacesOpt) <- Rx { (this.camera(), rxSurfaces()) }
          camera <- cameraOpt
          surfaces <- surfacesOpt
    } {
      Future {
        createCaptureSessionSemaphore.acquire()
        debug(s"Creating preview session using $camera")

        previewSession() foreach { _.close() }
        previewSession() = None
        runOnUiThread {
          camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback {
            override def onConfigured(session: CameraCaptureSession) {
              debug(s"Preview session configured: $session")
              previewSession() = Option(session)
              createCaptureSessionSemaphore.release()
            }

            override def onConfigureFailed(session: CameraCaptureSession) {
              debug("Preview session configuration failed")
              createCaptureSessionSemaphore.release()
            }

            override def onClosed(session: CameraCaptureSession) {
              debug(s"Preview session closed: $session")
              previewSession() = None
            }
          }, null)
        }
      }
    }
  }

  observe {
    for { (cameraOpt, mrOpt, previewSurfaceOpt, previewSessionOpt) <- Rx { (this.camera(), this.meteringRectangle(), this.previewSurface(), this.previewSession()) }
          camera <- cameraOpt
          mr <- mrOpt
          previewSurface <- previewSurfaceOpt
          previewSession <- previewSessionOpt
    } {
      debug(s"Triggering metering using $camera")
      val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      if (autoFocus()) {
        request.set(CONTROL_AF_MODE, CONTROL_AF_MODE_AUTO)
        request.set(CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_START)
        request.set(CONTROL_AF_REGIONS, Array[MeteringRectangle](mr))
      } else {
        request.set(CONTROL_AF_MODE, CONTROL_AF_MODE_OFF)
        request.set(LENS_FOCUS_DISTANCE, focusDistance())
      }

      if (autoExposure()) {
        request.set(CONTROL_AE_MODE, CONTROL_AE_MODE_ON)
        request.set(CONTROL_AE_PRECAPTURE_TRIGGER, CONTROL_AE_PRECAPTURE_TRIGGER_START)
        request.set(CONTROL_AE_REGIONS, Array[MeteringRectangle](mr))
      } else {
        request.set(CONTROL_AE_MODE, CONTROL_AE_MODE_OFF)
        request.set(SENSOR_SENSITIVITY, iso())
        request.set(SENSOR_EXPOSURE_TIME, exposureTime())
      }
      request.addTarget(previewSurface)

      metering() = true
      previewSession.capture(request.build(), null, null)
    }
  }

  val setMeteringRectangle = (v: View, e: MotionEvent) => {
    val meteringRectangleSize = 300
    val left = activeArraySize.left
    val right = activeArraySize.right
    val top = activeArraySize.top
    val bottom = activeArraySize.bottom

    val x = e.getX / v.getWidth
    val y = e.getY / v.getHeight
    val mr = new MeteringRectangle(
      0 max (left + (right - left) * y - meteringRectangleSize / 2).round,
      0 max (bottom - (bottom - top) * x - meteringRectangleSize / 2).round,
      meteringRectangleSize, meteringRectangleSize, 1
    )

    meteringRectangle() = Option(mr)
  }

  observe { camera foreach { c => if (!c.isDefined) {
    captureMode() match {
      case vm: VideoMode =>
        if (vm.recording()) vm.stopRecording()
        vm.releaseRecorder()
        captureMode() = new PhotoMode
      case _ =>
    }
  }}}

  lazy val orientationEventListener = new OrientationEventListener(this) {
    var lastOrientation = windowManager.getDefaultDisplay.getRotation
    override def onOrientationChanged(ignored: Int) = {
      val orientation = windowManager.getDefaultDisplay.getRotation
      if (orientation != lastOrientation) {
        lastOrientation = orientation
        setPreviewTransform(orientation)
      }
    }
  }

  val dumpCameraInfo = () =>
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

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)

    if (List(0, 2) contains windowManager.getDefaultDisplay.getRotation) {
      finish()
      return
    }

    if (Log.isLoggable("lcamera", Log.VERBOSE)) {
      dumpCameraInfo()
    }

    val capabilities = characteristics.get(REQUEST_AVAILABLE_CAPABILITIES)
    val requiredCapabilities = List(
      REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
      REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING,
      REQUEST_AVAILABLE_CAPABILITIES_RAW)

    if (!(requiredCapabilities forall { capabilities contains _ })) {
      toast("L Camera is not supported on your device")
      finish()
      return
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

    jpegImageReader.setOnImageAvailableListener(new OnImageAvailableListener {
      override def onImageAvailable(reader: ImageReader) { jpegImages.write(reader.acquireNextImage()) }
    }, null)

    rawImageReader.setOnImageAvailableListener(new OnImageAvailableListener {
      override def onImageAvailable(reader: ImageReader) { rawImages.write(reader.acquireNextImage()) }
    }, null)

    yuvImageReader.setOnImageAvailableListener(new OnImageAvailableListener {
      override def onImageAvailable(reader: ImageReader) { yuvImages.write(reader.acquireNextImage()) }
    }, null)

    getWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    val prefs = new Preferences(getSharedPreferences("lcamera", Context.MODE_PRIVATE))
    prefs.Boolean.autoFocus.foreach { autoFocus() = _ }
    prefs.Float.focusDistance.foreach { focusDistance() = _ }
    prefs.Boolean.autoExposure.foreach { autoExposure() = _ }
    prefs.Int.isoIndex.foreach { isoIndex() = _ }
    prefs.Int.exposureTimeIndex.foreach { exposureTimeIndex() = _ }
    prefs.Int.burst.foreach { burst() = _ }
    prefs.Boolean.focusStacking.foreach { focusStacking() = _ }
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
    prefs.Boolean.burstSaveDngJpeg.foreach { b => burstSaveDngJpeg() = if (b) Dng else Jpeg }

    orientationEventListener.enable()
  }

  override def onResume() {
    super.onResume()

    val listener = new CameraDevice.StateCallback {
      override def onOpened(device: CameraDevice) {
        debug(s"Camera opened: $device")
        camera() = Option(device)
      }

      override def onError(device: CameraDevice, error: Int) {
        debug(s"Camera error: $device, $error")
        device.close()
        longToast(s"Unable to open camera ($error)")
        MainActivity.this.finish()
      }

      override def onDisconnected(device: CameraDevice) {
        debug(s"Camera disconnected: $device")
        device.close()
      }

      override def onClosed(device: CameraDevice) {
        debug(s"Camera closed: $device")
        camera() = None
        previewSession() = None
      }
    }
    cameraManager.openCamera(cameraId, listener, null)
  }

  override def onPause() {
    super.onPause()

    camera().foreach(_.close())
    camera() = None
  }

  override def onStop() {
    super.onStop()

    val prefs = new Preferences(getSharedPreferences("lcamera", Context.MODE_PRIVATE))
    prefs.autoFocus = autoFocus()
    prefs.focusDistance = focusDistance()
    prefs.autoExposure = autoExposure()
    prefs.isoIndex = isoIndex()
    prefs.exposureTimeIndex = exposureTimeIndex()
    prefs.burst = burst()
    prefs.focusStacking = focusStacking()
    prefs.exposureBracketing = exposureBracketing()
    prefs.vcWidth = userVideoConfiguration().width
    prefs.vcHeight = userVideoConfiguration().height
    prefs.vcFps = userVideoConfiguration().fps
    prefs.vcBitrate = userVideoConfiguration().bitrate
    prefs.saveDng = saveDng()
    prefs.burstSaveDngJpeg = burstSaveDngJpeg() match { case Dng => true ; case Jpeg => false }
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

  def resolveAttr(attr: Int): Int = {
    val ta = obtainStyledAttributes(Array[Int](attr))
    val resId = ta.getResourceId(0, 0)
    ta.recycle()
    resId
  }
}

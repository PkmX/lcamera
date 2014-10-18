package pkmx.lcamera

import collection.JavaConversions._
import java.io.FileOutputStream
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Channel, ExecutionContext, Future}
import scala.collection.immutable.Vector
import scala.language.{existentials, implicitConversions}
import scala.util.control.NonFatal

import android.animation.{AnimatorSet, Animator, AnimatorListenerAdapter}
import android.content.Context
import android.graphics._
import android.hardware.camera2._
import android.hardware.camera2.CameraCharacteristics._
import android.hardware.camera2.CameraMetadata._
import android.hardware.camera2.CaptureRequest._
import android.hardware.camera2.params._
import android.media.{Image, MediaScannerConnection, ImageReader}
import android.media.ImageReader.OnImageAvailableListener
import android.os._
import android.text.format.Time
import android.view._
import android.view.animation.{Animation, TranslateAnimation}
import android.view.animation.Animation.AnimationListener
import android.widget._
import android.widget.ImageView.ScaleType

import com.melnykov.fab.FloatingActionButton
import org.scaloid.common._
import rx._
import rx.ops._

object Utils {
  implicit val execCtx = ExecutionContext.fromExecutor(AsyncTask.THREAD_POOL_EXECUTOR)

  implicit def requestKeyCovariant[T <: Any](k: CaptureRequest.Key[T]) = k.asInstanceOf[CaptureRequest.Key[Any]]
  implicit def resultKeyCovariant[T <: Any](k: CaptureRequest.Key[T]) = k.asInstanceOf[CaptureResult.Key[Any]]

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
}

import Utils._

class MainActivity extends SActivity {
  override implicit val loggerTag = LoggerTag("lcamera")
  lazy val cameraManager = getSystemService(Context.CAMERA_SERVICE).asInstanceOf[CameraManager]
  lazy val cameraId = cameraManager.getCameraIdList()(0)
  lazy val characteristics = cameraManager.getCameraCharacteristics(cameraId)
  lazy val streamConfigurationMap = characteristics.get(SCALER_STREAM_CONFIGURATION_MAP)
  lazy val minFocusDistance = characteristics.get(LENS_INFO_MINIMUM_FOCUS_DISTANCE)
  lazy val isoRange = characteristics.get(SENSOR_INFO_SENSITIVITY_RANGE)
  lazy val exposureTimeRange = characteristics.get(SENSOR_INFO_EXPOSURE_TIME_RANGE)

  lazy val jpegSize = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)(0)
  lazy val rawSize = streamConfigurationMap.getOutputSizes(ImageFormat.RAW_SENSOR)(0)
  lazy val jpegImageReader = ImageReader.newInstance(jpegSize.getWidth, jpegSize.getHeight, ImageFormat.JPEG, 1)
  lazy val rawImageReader = ImageReader.newInstance(rawSize.getWidth, rawSize.getHeight, ImageFormat.RAW_SENSOR, 10)
  lazy val jpegSurface = jpegImageReader.getSurface
  lazy val rawSurface = rawImageReader.getSurface
  val jpegImages = new Channel[Image]
  val rawImages = new Channel[Image]

  val camera = NoneVar[CameraDevice]
  val previewSurface = NoneVar[Surface]
  val previewSession = NoneVar[CameraCaptureSession]
  val meteringRectangle = NoneVar[MeteringRectangle]
  val capturing = Var(false)
  val burst = Var(8)

  val autoFocus = Var(true)
  val focusDistance = Var(0f)
  val autoExposure = Var(true)
  val isoMap = Vector(100, 200, 400, 800, 1600, 3200, 6400, 10000)
  val isoIndex = Var(0) // ISO 100
  val iso = isoIndex.map(isoMap)
  val autoIso = Var(100)
  val exposureTimeMap = Vector(2, 4, 6, 8, 15, 30, 60, 100, 125, 250, 500, 1000, 2000, 4000, 8000)
  val exposureTimeIndex = Var(6) // 1/60s
  val exposureTime = Rx { 1000000000l / exposureTimeMap(exposureTimeIndex()) }
  val autoExposureTime = Var(1000000000l)
  val metering = Var(false)

  lazy val textureView = new TextureView(ctx) with TraitView[TextureView] {
    val basis = this
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
        val textureSize = streamConfigurationMap.getOutputSizes(texture.getClass)(0)
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

  val setPreviewTransform: Int => Unit = (rotation) => {
    if (textureView.isAvailable) {
      textureView.setTransform {
        val textureSize = streamConfigurationMap.getOutputSizes(textureView.getSurfaceTexture.getClass)(0)
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
    imageDrawable = R.drawable.ic_camera
    backgroundColor = Color.parseColor("#4285f4")
    scaleType = ScaleType.FIT_CENTER
    onClick { capture() }
  }

  lazy val toolbar = new SLinearLayout {
    backgroundColor = Color.parseColor("#fafafa")
    gravity = Gravity.CENTER
    visibility = View.INVISIBLE
    enabled = false

    += (new STextView {
      text = "AF"
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
      onClick {
        afView.enabled = true
        circularReveal(afView, this.left + this.getWidth / 2, this.top + this.getHeight / 2, afView.width).start()
      }
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).<<.wrap.>>)

    += (new STextView {
      text = "AE"
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
      onClick {
        aeView.enabled = true
        circularReveal(aeView, this.left + this.getWidth / 2, this.top + this.getHeight / 2, aeView.width).start()
      }
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).<<.wrap.>>)

    += (new STextView {
      text = "Burst"
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).<<.wrap.>>)

    += (new Switch(ctx) {
      val obs = burst.foreach(n => setChecked(n > 1))
      setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener {
        override def onCheckedChanged(v: CompoundButton, checked: Boolean) {
          burst() = if (checked) 8 else 1
        }
      })
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).<<.wrap.>>)
  }

  lazy val afView = new SLinearLayout {
    backgroundColor = Color.parseColor("#fafafa")
    gravity = Gravity.CENTER
    visibility = View.INVISIBLE
    enabled = false

    += (new STextView {
      text = "AF"
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).<<.wrap.>>)
    += (new Switch(ctx) {
      val obs = autoFocus.foreach(setChecked)
      setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener {
        override def onCheckedChanged(v: CompoundButton, checked: Boolean) {
          autoFocus() = checked
        }
      })
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).<<.wrap.>>)
    += (new SSeekBar {
      max = (minFocusDistance * 100).round
      val obs = (autoFocus.foreach(af => enabled = !af),
                 focusDistance.foreach(fd => setProgress((fd * 100).round)))
      onProgressChanged((seekbar: SeekBar, value: Int, fromUser: Boolean) => {
        if (fromUser)
          focusDistance() = value.toFloat / 100
      })
    }.padding(8.dip, 8.dip, 8.dip, 8.dip).<<(MATCH_PARENT, WRAP_CONTENT).>>)
  }.padding(16.dip, 0, 16.dip, 0)

  lazy val aeView = new SLinearLayout {
    backgroundColor = Color.parseColor("#fafafa")
    gravity = Gravity.CENTER
    visibility = View.INVISIBLE
    enabled = false

    += (new STextView {
      text = "AE"
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16.sp
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).<<.wrap.>>)

    += (new Switch(ctx) {
      val obs = autoExposure.foreach(setChecked)
      setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener {
        override def onCheckedChanged(v: CompoundButton, checked: Boolean) {
          autoExposure() = checked
        }
      })
    }.padding(8.dip, 16.dip, 8.dip, 16.dip).<<.wrap.>>)

    def prevButton(f: => Unit) = new SImageView {
      backgroundResource = resolveAttr(android.R.attr.selectableItemBackground)
      val obs = autoExposure.foreach { ae =>
        enabled = !ae
        imageDrawable = if (ae) R.drawable.ic_navigation_previous_item_disabled else R.drawable.ic_navigation_previous_item
      }

      onClick(f)
    }

    def nextButton(f: => Unit) = new SImageView {
      backgroundResource = resolveAttr(android.R.attr.selectableItemBackground)
      val obs = autoExposure.foreach { ae =>
        enabled = !ae
        imageDrawable = if (ae) R.drawable.ic_navigation_next_item_disabled else R.drawable.ic_navigation_next_item
      }

      onClick(f)
    }

    += (prevButton { exposureTimeIndex() = Math.max(exposureTimeIndex() - 1, 0) }.<<(32.dip, 32.dip).>>)
    += (new STextView {
      val obs = (exposureTimeIndex.foreach(v => text = s"1/${exposureTimeMap(v)}"),
                 autoExposure.foreach(ae => textColor = if (ae) Color.parseColor("#d0d0d0") else Color.parseColor("#000000")))
    }.padding(4.dip, 16.dip, 4.dip, 16.dip).<<.wrap.>>)
    += (nextButton { exposureTimeIndex() = Math.min(exposureTimeMap.length - 1, exposureTimeIndex() + 1) }.<<(32.dip, 32.dip).>>)

    += (prevButton { isoIndex() = Math.max(isoIndex() - 1, 0) }.<<(32.dip, 32.dip).>>)
    += (new STextView {
      val obs = (iso.foreach(v => text = s"ISO $v"),
                 autoExposure.foreach(ae => textColor = if (ae) Color.parseColor("#d0d0d0") else Color.parseColor("#000000")))
    }.padding(4.dip, 16.dip, 4.dip, 16.dip).<<.wrap.>>)
    += (nextButton { isoIndex() = Math.min(isoMap.length - 1, isoIndex() + 1) }.<<(32.dip, 32.dip).>>)
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
    val obs = capturing foreach { c => visibility = if (c) View.VISIBLE else View.INVISIBLE}
  }

  val manualFocusDistance = focusDistance.filter(_ => !this.autoFocus())
  val startPreview =
    for { (cameraOpt, previewSurfaceOpt, previewSessionOpt, autoFocus, focusDistance, autoExposure, iso, exposureTime, metering)
          <- Rx {(this.camera(), this.previewSurface(), this.previewSession(),
                  this.autoFocus(), this.focusDistance(),
                  this.autoExposure(), this.iso(), this.exposureTime(), this.metering())}
           camera <- cameraOpt
           previewSurface <- previewSurfaceOpt
           previewSession <- previewSessionOpt
    } {
      debug(s"Starting preview using $camera")
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
      request.addTarget(previewSurface)

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

  val createPreview =
    for { (cameraOpt, previewSurfaceOpt) <- Rx { (this.camera(), this.previewSurface()) }
          camera <- cameraOpt
          previewSurface <- previewSurfaceOpt
        } {
      debug(s"Creating preview session using $camera")
      camera.createCaptureSession(List(previewSurface), new CameraCaptureSession.StateCallback {
        override def onConfigured(session: CameraCaptureSession) {
          debug(s"Preview session configured: ${session.toString}")
          previewSession() = Option(session)
        }

        override def onConfigureFailed(session: CameraCaptureSession) {
          debug("Preview session configuration failed")
        }
      }, null)
    }

  val triggerMetering =
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

  val setMeteringRectangle = (v: View, e: MotionEvent) => {
    val meteringRectangleSize = 300
    val activeArraySize = characteristics.get(SENSOR_INFO_ACTIVE_ARRAY_SIZE)
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

  val capture = () =>
    for { camera <- this.camera() } {
      debug(s"Starting capture using $camera")
      capturing() = true
      previewSession() foreach { _.abortCaptures() }

      val time = new Time
      time.setToNow()
      val filePathBase = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + time.format("/Camera/IMG_%Y%m%d_%H%M%S")
      val orientation = windowManager.getDefaultDisplay.getRotation

      val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

      request.set(CONTROL_MODE, CONTROL_MODE_AUTO)

      request.set(CONTROL_AF_MODE, CONTROL_AF_MODE_OFF)
      request.set(LENS_FOCUS_DISTANCE, focusDistance())

      request.set(CONTROL_AE_MODE, CONTROL_AE_MODE_OFF)
      request.set(SENSOR_SENSITIVITY, if (autoExposure()) autoIso() else iso())
      request.set(SENSOR_EXPOSURE_TIME, if (autoExposure()) autoExposureTime() else exposureTime())

      request.set(JPEG_QUALITY, 95.toByte)
      request.set(JPEG_ORIENTATION, orientation match {
        case Surface.ROTATION_0 => 90
        case Surface.ROTATION_90 => 0
        case Surface.ROTATION_180 => 270
        case Surface.ROTATION_270 => 180
        case _ => 0
      })
      request.set(STATISTICS_LENS_SHADING_MAP_MODE, STATISTICS_LENS_SHADING_MAP_MODE_ON) // Required for RAW capture

      val targetSurfaces = if (burst() > 1) List(rawSurface) else List(jpegSurface, rawSurface)
      targetSurfaces map { request.addTarget }

      previewSession() = None
      debug(s"Creating capture session with $targetSurfaces")
      camera.createCaptureSession(targetSurfaces, new CameraCaptureSession.StateCallback {
        override def onConfigured(session: CameraCaptureSession) {
          debug(s"Capture session configured: $session")
          val tasks = new ListBuffer[Future[Unit]]
          var frameNumber = 0
          val rawResults = new Channel[(String, TotalCaptureResult)]

          session.captureBurst(List.fill(burst())(request.build()), new CameraCaptureSession.CaptureCallback {
            override def onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
              debug(s"Capture completed: " +
                    s"focus = ${result.get(CaptureResult.LENS_FOCUS_DISTANCE)}/${request.get(LENS_FOCUS_DISTANCE)} " +
                    s"iso = ${result.get(CaptureResult.SENSOR_SENSITIVITY)}/${request.get(SENSOR_SENSITIVITY)} " +
                    s"exposure = ${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)}/${request.get(SENSOR_EXPOSURE_TIME)}")

              rawResults.write((if (burst() > 1) s"${filePathBase}_$frameNumber.dng" else s"$filePathBase.dng", result))
              frameNumber += 1
            }

            override def onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
              if (burst() == 1) {
                tasks += Future {
                  val image = jpegImages.read
                  val jpgFilePath = s"$filePathBase.jpg"
                  val jpegBuffer = image.getPlanes()(0).getBuffer
                  val bytes = new Array[Byte](jpegBuffer.capacity)
                  jpegBuffer.get(bytes)
                  image.close()
                  new FileOutputStream(jpgFilePath).write(bytes)
                  MediaScannerConnection.scanFile(MainActivity.this, Array[String](jpgFilePath), null, null)
                  debug("JPEG saved")
                }
              }

              tasks += Future {
                for (n <- 1 to burst()) {
                  val image = rawImages.read
                  val (filePath, result) = rawResults.read

                  val dngCreator = new DngCreator(characteristics, result).setOrientation(orientation)
                  dngCreator.writeImage(new FileOutputStream(filePath), image)
                  dngCreator.close()
                  image.close()
                  MediaScannerConnection.scanFile(MainActivity.this, Array[String](filePath), null, null)
                  debug("DNG saved")
                }
              }

              tasks foreach { _ onFailure { case NonFatal(e) => e.printStackTrace() } }
              tasks reduce { (_ : Future[Any]) zip (_ : Future[Any]) } onComplete { _ => runOnUiThread { MainActivity.this.capturing() = false } }
              createPreview.trigger()
            }

            override def onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
              debug("Capture failed")
              MainActivity.this.capturing() = false
              createPreview.trigger()
            }
          }, null)
        }

        override def onConfigureFailed(session: CameraCaptureSession) {
          debug("Capture session configuration failed")
        }
      }, null)
    }

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

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)

    contentView = new SRelativeLayout {
      += (textureView.<<.alignParentLeft.alignParentTop.alignParentBottom.leftOf(captureButton).>>)
      += (toolbar.<<(MATCH_PARENT, WRAP_CONTENT).alignParentLeft.leftOf(captureButton).alignParentBottom.>>)
      += (afView.<<(MATCH_PARENT, WRAP_CONTENT).alignParentLeft.leftOf(captureButton).alignParentBottom.>>)
      += (aeView.<<(MATCH_PARENT, WRAP_CONTENT).alignParentLeft.leftOf(captureButton).alignParentBottom.>>)
      += (captureButton.<<(96.dip, MATCH_PARENT).alignParentRight.alignParentTop.alignParentBottom.>>)
      += (progressBar.<<(MATCH_PARENT, WRAP_CONTENT).alignParentLeft.leftOf(captureButton).alignParentBottom.marginBottom(-4.dip).>>)
      += (fab.<<.wrap.alignParentLeft.alignParentBottom.marginLeft(fabMargin).marginBottom(fabMargin).>>)
    }

    jpegImageReader.setOnImageAvailableListener(new OnImageAvailableListener {
      override def onImageAvailable(reader: ImageReader) { jpegImages.write(reader.acquireNextImage()) }
    }, null)

    rawImageReader.setOnImageAvailableListener(new OnImageAvailableListener {
      override def onImageAvailable(reader: ImageReader) { rawImages.write(reader.acquireNextImage()) }
    }, null)

    val prefs = new Preferences(getSharedPreferences("lcamera", Context.MODE_PRIVATE))
    prefs.Boolean.autoFocus.foreach { autoFocus() = _ }
    prefs.Float.focusDistance.foreach { focusDistance() = _ }
    prefs.Boolean.autoExposure.foreach { autoExposure() = _ }
    prefs.Int.isoIndex.foreach { isoIndex() = _ }
    prefs.Int.exposureTimeIndex.foreach { exposureTimeIndex() = _ }
    prefs.Int.burst.foreach { burst() = _ }

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
        camera() = None
        previewSession() = None
      }

      override def onDisconnected(device: CameraDevice) {
        debug(s"Camera disconnected: $device")
        camera() = None
        previewSession() = None
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
    previewSession() = None
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
  }

  override def onBackPressed() {
    if (afView.enabled) {
      slideDownHide(afView)
      afView.enabled = false
    } else if (aeView.enabled) {
      slideDownHide(aeView)
      aeView.enabled = false
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
      capture()
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
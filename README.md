# L Camera

![Screenshot](screenshot.jpg?raw=true)

**L Camera** is an open-source experimental camera app for Android L devices using the new `android.hardware.camera2` API. Currently, the only supported device is Nexus 5 and Nexus 6 running Android 5.0 Lollipop.

*Please note that this app is intended to test and study new features of the camera API, it is not for general uses as it lacks many basic camera features (location tagging, white balance, photo review, flash control, etc).*

[See what you can achieve on the Nexus 5 with the new API](http://imgur.com/a/qQkkR).

[See how the new API can greatly enhance low-light photography with the right settings and tools](http://imgur.com/a/ftvBH).

Check out some 60-fps video recording samples: [1](https://www.youtube.com/watch?v=T6D1Qu7Q23o) [2](https://www.youtube.com/watch?v=kTL3FfGV2k4).

Slow motion video recorded with a Nexus 5: https://www.youtube.com/watch?v=iKAvN-x53jM

## Features

* True manual focus (adjustable focus distance)
* Manual exposure time (0.8" to 1/75000)
* Manual ISO (40 to 10000)
* DNG output support
* 30-fps full-resolution burst capture with focus stacking & exposure bracketing
* 30-fps full-resolution (3264x2448) video recording on Nexus 5
* 30-fps 4K UHD (3840x2160) video recording on Nexus 6
* Experimental 60-fps video recording at 1080p on Nexus 5
* Material design

## Installation

You can either install the pre-built debug APK from the [release](https://github.com/PkmX/lcamera/releases) page, or build and install the APK by yourself.

## Usage

Just use it like any camera! Tap the floating button on the left-bottom corner to bring up settings:

* Focus
 * Auto Focus: Whether the auto focus mechanism is enabled (tap on the preview to focus on a specific point).
 * Focus Distance: Manually control focus distance if auto focus is turned off.
* Exposure
 * Auto Exposure: Whether auto exposure and auto white balance routines are enabled (tap on the preview to start a metering sequence).
 * Shutter Speed: Control the exposure time. (Setting a slow shutter speed will affect preview frame rate)
 * ISO: Control the sensitivity of the sensor.
* Burst
 * Burst: Control whether burst capturing is enabled. If enabled, the camera will capture 7 images at maximum resolution at 30 fps.
 * Focus Stacking: If enabled, the camera will capture a series of images ranging from infinity focus and to the nearest focus distance possible.
 * Exposure Bracketing: If enabled, the camera will capture a series of 7 images ranging from -3 to +3 EV of the standard expousre. (Only the shutter speed is varied, the ISO stays the same)
 * DNG/JPEG: Specify the output format for burst capture.
* Photo/Video: Switch between photo capturing and video recording mode. The video is encoded with H.264/AVC for video and 44.1khz 320kbps AAC-LC for audio in MP4 container. Focus distance and exposure are adjustable during recording.
* Settings
 * Video Resolution: Configurate video resolution, fps and encoding bitrate. (See below for 60fps recording)
 * Save DNG: Specify whether the DNG output is saved in single capture mode.

If you are looking for a stopwatch to test the burst capture feature, check out [this jsfiddle](http://jsfiddle.net/jw2z5eeu/).

After capturing, output files will be saved to the `/sdcard/DCIM/Camera/` directory. Note that each DNG image is 15.36 MiB in size, so make sure you have plenty of free space available!

### Working with DNG files

Most RAW post-processing programs should be able to open them. While Adobe Lightroom is probably the most popular RAW editor, both [darktable](http://www.darktable.org/) (Linux, Mac) and [RawTherapee](http://rawtherapee.com/) (Windows, Linux, Mac) are both free alternatives that also offer very powerful editing capabilities.

[A short DNG editing tutorial using RawTherapee](http://imgur.com/a/ZpEPP#0).

### 60 FPS Recording
First, see [pkmx/lcamera#4](https://github.com/PkmX/lcamera/issues/4#issuecomment-61356241) about limitations of this modification. This modification is only available for Nexus 5.

To enable 60fps recording, a system library `/system/lib/libmmcamera_imx179.so` needs to be replaced with a modified version. The following is a simplified walkthrough of the process. *Note that this is a very hacky solution and I'm not responsible for any damages done to your system or device. Approach at your own risk and make sure you understand what you are doing.*

1. You must have root access and [busybox](https://play.google.com/store/apps/details?id=stericson.busybox) installed on your Nexus 5. (The latter is not strictly required, but makes the process easier as it provides `install` and `killall`.)
2. Download `libmmcamera_imx179_lrx21o.so` from the [release](https://github.com/PkmX/lcamera/releases) page and transfer it to the device. (The following assumes that it is located in `/sdcard/`.)
3. Launch a root shell.
4. Make a backup of the original library first: `cp /system/lib/libmmcamera_imx179.so /sdcard/libmmcamera_imx179_original.so`
5. Run `mount -o remount,rw /system` to re-mount the `/system` partition for read-write.
6. Replace the library: `install -m644 /sdcard/libmmcamera_imx179_lrx21o_60hz.so /system/lib/libmmcamera_imx179.so`
7. Run `mount -o remount,ro /system` to re-mount `/system` as read-only again.
8. Restart both the camera daemon and media server: `killall mm-qcamera-daemon mediaserver`

The camera should now be able to record at 60fps and you can choose 60fps options in the settings menu on L Camera. All other camera apps will most likely be broken at this point. If you want to undo the modification, simply redo step 5~8 and copy the original library you backup-ed in step 4 instead.

For details about the library modification and a utility to patch your own, see contents of the `libmmcamera_imx179_mod` directory.

## FAQ

### Does it run on Nexus 4/7/10 or other phones that have received Lollipop update?
It seems that none of those devices fully support the new API as of now (2014/11/17). If you want to verify, enable verbose output outlined in the "Debugging" section below, and check the output from `logcat`. You should see a dump of your device cameras' capabilities like [this](https://gist.github.com/PkmX/fefff90bab3b6eb2847f) when L Camera is started. Your camera's `android.request.availableCapabilities` must include 1 (MANUAL_SENSOR), 2 (MANUAL_POST_PROCESSING) and 3 (RAW) for L Camera to work.

### Why do I get the 'Cannot parse package' error while installing?
Make sure the downloaded apk is not corrupted, as it seems some browsers download GitHub's webpage instead of the actual apk. You must also have the Lollipop running on your device.

### Does it need root?
No. However, it is needed if you want to record videos at 60fps on the Nexus 5 as a system library needs to be modified.

### Will you implement feature X?
Open an issue (one per feature please) on the tracker and I will see what I can do. However, the priority of development is investigating new ways to use the camera hardware rather than reimplement features that have been possible, or things that can be done in post-processing.

## Hacking/Technical

The app is written in the [Scala](http://www.scala-lang.org/) programming language and uses the following libraries/tools:

* [SBT](http://www.scala-sbt.org/): build tool.
* [Android SDK Plugin for SBT](https://github.com/pfn/android-sdk-plugin): for building Android apps with SBT.
* [Scaloid](https://github.com/pocorall/scaloid/): for UI layout and various helpers.
* [Scala.Rx](https://github.com/lihaoyi/scala.rx): for reactive value propagation.
* [Floating Action Button](https://github.com/makovkastar/FloatingActionButton): for the floating action button (FAB) featured in material design.

### How to build

You must have both **scala 2.11.4** and **sbt >= 0.13** installed.

To build the app (the resulting APK will be placed in the `bin/` directory):

    $ sbt package

To build and run the app on device (assuming you have `adb` and developer mode enabled):

    $ sbt run

### Debugging

To see debug outputs, set allowed logging priority of `lcamera` tag to `VERBOSE`:

    $ adb shell setprop log.tag.lcamera VERBOSE

## Issues

Please report any bugs or feature requests on GitHub's issue tracker.

## Credits

* Martin Wawro: for his support in enabling 60fps video recording on Nexus 5

## License

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.

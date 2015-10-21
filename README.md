# L Camera

![Screenshot](screenshot.jpg?raw=true)

**L Camera** is an open-source experimental camera app for Android L devices using the new `android.hardware.camera2` API. Currently, the only supported device is Nexus 5 and Nexus 6 running Android 5.0 Lollipop.

*Please note that this app is intended to test and study new features of the camera API, it is not for general uses as it lacks many basic camera features (location tagging, white balance, photo review, flash control, etc).*

## Features

* True manual focus (adjustable focus distance)
* Manual exposure time (0.8" to 1/75000)
* Manual ISO (40 to 10000)
* DNG output support
* 30-fps full-resolution burst mode (up to 20 images) with optional exposure bracketing
* Semi-bulb mode
* 30-fps full-resolution (3264x2448) video recording on Nexus 5
* 30-fps 4K UHD (3840x2160) video recording on Nexus 6
* Experimental 60-fps video recording at 1080p on Nexus 5
* Experimental 120-fps video recording at 800x600 on Nexus 5
* Material design

### Tour

* [See what you can achieve on the Nexus 5 with the new API](http://imgur.com/a/qQkkR).
* [See how the new API can greatly enhance low-light photography with the right settings and tools](http://imgur.com/a/ftvBH).
* [Astrophotograhpy with Nexus 5](http://imgur.com/a/BXMGu)
* Check out some 60-fps video recording samples: [1](https://www.youtube.com/watch?v=T6D1Qu7Q23o) [2](https://www.youtube.com/watch?v=kTL3FfGV2k4)
* [Slow motion video recorded with a Nexus 5](https://www.youtube.com/watch?v=iKAvN-x53jM)
* [Feature comparison with other camera apps by xda-developers](http://www.xda-developers.com/top-4-camera-apps-for-lollipops-new-api/)

## Installation

You can either install the pre-built debug APK from the [release](https://github.com/PkmX/lcamera/releases) page, or build and install the APK by yourself.

## Usage

Just use it like any camera! Tap other four button at the bottom to bring up settings:

* Focus
    * AF/MF: Whether the auto focus mechanism is enabled (tap on the preview to focus on a specific point).
    * Focus Distance: Manually control focus distance if auto focus is turned off.
* Exposure
    * Tap on either shutter speed or ISO values to enable manual exposure control.
    * Aperture: Show the aperture of the camera. Currently, all devices that support the new `camera2` API have fixed aperture, and therefore this setting cannot be changed.
    * Shutter Speed: Control shutter speed of the sensor. (Setting a slow shutter speed will affect preview frame rate)
    * ISO: Control the sensitivity of the sensor.
* Mode
    * Photo mode: Capture a single image at the highest quality possible.
    * Burst mode: Capture up to 20 image at maximum resolution at 30 fps with optional exposure bracketing.
    * Bulb mode: Keep capturing DNG images until manually stopped. Note that as the Nexus 5's internal memory writing speed cannot keep up with the rate the camera pushes out new images even at the slowest shutter speed, the camera waits until the previous image is saved before starting a new capture to avoid filling the buffers.
    * Video mode: Videos are encoded with H.264/AVC for video and 44.1khz 320kbps AAC-LC for audio in MP4 container. Focus distance and exposure settings are adjustable during recording.
* Settings
    * Photo Mode
        * Save DNG: Toggle whether a DNG image is saved in single capture mode.
    * Burst Mode
        * No. of Images: Number of images to be captured in burst. Note that the hardware may not be able to sustain 30fps for all the burst shots. For instance, the frame rate drops significantly after the 7th shot on the Nexus 5.
        * Exposure Bracketing: Number of stops that will be varied between each capture in a burst sequence. Only the shutter speed is varied, the ISO stays the same. For example, with 7 images per burst and 1/3 EV exposure bracketing setting, the camera will capture 7 images at -1, -2/3, -1/3, 0, 1/3, 2/3, 1 EV.
        * Save DNG: Specify the output format for burst capture.
    * Video Mode
        * Video Resolution: Configure video resolution, fps and encoding bitrate. (See below for 60fps recording)
   Switch between photo capturing and video recording mode.

If you are looking for a stopwatch to test the burst capture feature, check out [this jsfiddle](http://jsfiddle.net/jw2z5eeu/).

After capturing, output files will be saved to the `/sdcard/DCIM/Camera/` directory. Note that each DNG image is 15.36 MiB in size, so make sure you have plenty of free space available!

### Working with DNG files

Most RAW post-processing programs should be able to open them. While Adobe Lightroom is probably the most popular RAW editor, both [darktable](http://www.darktable.org/) (Linux, Mac) and [RawTherapee](http://rawtherapee.com/) (Windows, Linux, Mac) are both free alternatives that also offer very powerful editing capabilities.

[A short DNG editing tutorial using RawTherapee](http://imgur.com/a/ZpEPP#0).

### 60/120 FPS Recording
First, see [pkmx/lcamera#4](https://github.com/PkmX/lcamera/issues/4#issuecomment-61356241) about limitations of this modification. This modification is only available for Nexus 5.

To enable 60/120fps recording, the system library `/system/lib/libmmcamera_imx179.so` needs to be replaced with a modified version. The following is a simplified walkthrough of the process. *Note that this is a very hacky solution and I'm not responsible for any damages done to your system or device. Approach at your own risk and make sure you understand what you are doing.*

1. You must have root access and [busybox](https://play.google.com/store/apps/details?id=stericson.busybox) installed on your Nexus 5. (The latter is not strictly required, but makes the process easier as it provides `install` and `killall`.)
2. Download the `libmmcamera_imx179_lrx21o_60hz.so` or ``libmmcamera_imx179_lrx21o_120hz.so` from the [release](https://github.com/PkmX/lcamera/releases) page and transfer it to the device. (The following assumes that it is located in `/sdcard/`.)
3. Launch a root shell.
4. Make a backup of the original library first: `cp /system/lib/libmmcamera_imx179.so /sdcard/libmmcamera_imx179_original.so`
5. Run `mount -o remount,rw /system` to re-mount the `/system` partition for read-write.
6. Replace the library: `install -m644 /sdcard/libmmcamera_imx179_lrx21o_60hz.so /system/lib/libmmcamera_imx179.so` (Replace `60hz` with `120hz` if you downloaded the 120fps one.)
7. Run `mount -o remount,ro /system` to re-mount `/system` as read-only again.
8. Restart both the camera daemon and media server: `killall mm-qcamera-daemon mediaserver`

The camera should now be able to record at 60/120 fps and you can choose 60/120fps options in the settings menu on L Camera. All other camera apps will most likely be broken at this point. If you want to undo the modification, simply redo step 5~8 and copy the original library you backup-ed in step 4 instead.

For details about the library modification and a utility to patch your own, see contents of the `libmmcamera_imx179_mod` directory.

## FAQ

### Does it run on Nexus 4/7/10 or other phones that have received Lollipop update?
It seems that none of those devices fully support the new API as of now (2014/11/17). If you want to verify, run `adb shell dumpsys media.camera` with your device connected and developer mode enabled. You should see a dump of your device cameras' capabilities like [this](https://gist.github.com/PkmX/fefff90bab3b6eb2847f). Your camera's `android.request.availableCapabilities` must include `MANUAL_SENSOR`, `MANUAL_POST_PROCESSING` and `RAW` for L Camera to work.

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
* [Circular Progress View](https://github.com/rahatarmanahmed/CircularProgressView): for the progress circle around the FAB.

### How to build

You must have both **scala 2.11.5** and **sbt >= 0.13** installed.

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

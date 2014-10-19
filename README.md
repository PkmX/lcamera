# L Camera

![Screenshot](screenshot.jpg?raw=true)

**L Camera** is an open-source experimental camera app for Android L devices using the new `android.hardware.camera2` API. Currently, the only supported device is Nexus 5 running Android Lollipop preview (LPX13D).

*Please note that this app is intended to test and study new features of the camera API, it is not for general uses as it lacks many basic camera features (location tagging, white balance, photo review, flash control, video recording, etc).*

[See what you can achieve on the Nexus 5 with the new API](http://imgur.com/a/qQkkR#0).

## Features

* True manual focus (adjustable focus distance)
* Manual exposure time (1/2, 1/4, 1/6, 1/8, 1/15, 1/30, 1/60, 1/100, 1/125, 1/250, 1/500, 1/1000, 1/2000, 1/4000, 1/8000)
* Manual ISO (100, 200, 400, 800, 1600, 3200, 6400, 10000)
* DNG output support
* 30fps full-resolution burst capture with focus stacking & exposure bracketing
* Material design

## Installation

You can either install the pre-built debug APK ([`lcamera-debug.apk`](lcamera-debug.apk?raw=true)) found in the repository, or build and install the APK by yourself.

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
 * Burst: Control whether burst capturing is enabled. If enabled, the camera will capture 7 DNG images at maximum resolution at 30 fps. (Note that JPEG output is disabled during burst capturing.)
 * Focus Stacking: If enabled, the camera will capture a series of images ranging from infinity focus and to the nearest focus distance possible.
 * Exposure Bracketing: If enabled, the camera will capture a series of 7 images ranging from -3 to +3 EV of the standard expousre. (Only the shutter speed is varied, the ISO stays the same)

If you are looking for a stopwatch to test the burst capture feature, check out [this jsfiddle](http://jsfiddle.net/jw2z5eeu/).

After capturing, both DNG and JPEG files will be saved in `/sdcard/DCIM/Camera/` directory. Note that each DNG image is 15.36 MiB in size, so make sure you have plenty of free space available!

### Working with DNG files

Most RAW post-processing programs should be able to open them. While Adobe Lightroom is probably the most popular RAW editor, both [darktable](http://www.darktable.org/) (Linux, Mac) and [RawTherapee](http://rawtherapee.com/) (Windows, Linux, Mac) are both free alternatives that also offer very powerful editing capabilities.

## How to build

You must have both **scala 2.11.2** and **sbt >= 0.13** installed.

To build the app (the resulting APK will be placed in the `bin/` directory):

    $ sbt package

To build and run the app on device (assuming you have `adb` and developer mode enabled):

    $ sbt run

## Hacking/Technical

The app is written in the [Scala](http://www.scala-lang.org/) programming language and uses the following libraries/tools:

* [SBT](http://www.scala-sbt.org/): build tool.
* [Android SDK Plugin for SBT](https://github.com/pfn/android-sdk-plugin): for building Android apps with SBT.
* [Scaloid](https://github.com/pocorall/scaloid/): for UI layout and various helpers.
* [Scala.Rx](https://github.com/lihaoyi/scala.rx): for reactive value propagation.
* [Floating Action Button](https://github.com/makovkastar/FloatingActionButton): for the floating action button (FAB) featured in material design.

The preview code probably needs some major refactoring to avoid calling `setRepeatingRequest()`, so we don't end up with unwanted requests in the pipeline.

To see debug outputs, set allowed logging priority of `lcamera` tag to `DEBUG`:

    $ adb shell setprop log.tag.lcamera DEBUG

## To-do

* Add exposure compensation.
* Support portrait orientation.
* Simulate exposure compensation in preview? Currently setting a long shutter speed will throttle the preview frame rate.

## Known Issues

* AWB does not work in manual exposure mode. You can workaround the problem by setting the white balance in AE mode and switching back to manual mode.
* There appears to be image distortion effects at very fast shutter speed.
* Image distrotion with focus stacking.

Please report any bugs on GitHub's issue tracker.

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

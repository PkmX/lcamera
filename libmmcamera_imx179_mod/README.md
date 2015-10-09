# libmmcamera_imx179_mod

This utility program patches the `libmmcamera_imx179.so` library from the MRA58K release and configure it for different resolution and fps.

## Build

You need clang >=3.5 (or other C++ compilers that support C++1z), boost >= 1.58.0 installed. Clone the `endian` submodule and issue:

    $ make

## Usage

To dump information about a library:

    $ ./imx179_patch libmmcamera_imx179.so
    libmmcamera_imx179.so: active_pixel_width=3280 active_pixel_height=2464 pixel_array_width=3440 pixel_array_height=2504 sensor_timing=260000000 output_timing=260000000 fps=30.18

To patch the library for 60fps recoding:

    $ ./imx179_patch libmmcamera_imx179.so --active-pixel-width=1940 --active-pixel-height=1232 --pixel-array-height=1252 --output-timing=130000000 --fps=60.36 -o libmmcamera_imx179_60hz.so
    libmmcamera_imx179.so: active_pixel_width=3280 active_pixel_height=2464 pixel_array_width=3440 pixel_array_height=2504 sensor_timing=260000000 output_timing=260000000 fps=30.18
    libmmcamera_imx179_60hz.so: active_pixel_width=1940 active_pixel_height=1232 pixel_array_width=3440 pixel_array_height=1252 sensor_timing=260000000 output_timing=130000000 fps=60.36

To patch the library for 120fps recording:

    $ ./imx179_patch libmmcamera_imx179.so --active-pixel-width=820 --active-pixel-height=616 --pixel-array-height=626 --output-timing=65000000 --fps=120.72 -o libmmcamera_imx179_120hz.so
    libmmcamera_imx179.so: active_pixel_width=3280 active_pixel_height=2464 pixel_array_width=3440 pixel_array_height=2504 sensor_timing=260000000 output_timing=260000000 fps=30.18
    libmmcamera_imx179_120hz.so: active_pixel_width=820 active_pixel_height=616 pixel_array_width=3440 pixel_array_height=626 sensor_timing=260000000 output_timing=65000000 fps=120.72

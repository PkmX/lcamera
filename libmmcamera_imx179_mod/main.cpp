#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <boost/endian/arithmetic.hpp>
#include <boost/program_options/options_description.hpp>
#include <boost/program_options/parsers.hpp>
#include <boost/program_options/positional_options.hpp>
#include <boost/program_options/variables_map.hpp>

namespace po = boost::program_options;
using namespace boost::endian;

// For Android 6.0 MRA58K
constexpr auto wh32_offset = 0x431C;
constexpr auto conf_offset = 0x4404;

// A struct containing various settings for camera hardware. There are
// actually two entries at `conf_offset`, however, only the first one is used.
//
// To double frame rate, the following fields need to be halved:
//   active_pixel_width
//   active_pixel_height
//   pixel_array_height
//   output_timing
//
// and the fps field need to be doubled as well.
struct [[gnu::packed]] configuration {
    little_int16_t active_pixel_width;
    little_int16_t active_pixel_height;
    little_int16_t pixel_array_width;
    little_int16_t pixel_array_height;
    little_int32_t sensor_timing;
    little_int32_t output_timing;
    std::uint8_t unknown[8];
    little_float32_t fps;
};

static_assert(sizeof(configuration) == 28);

std::ostream& operator<<(std::ostream& out, const configuration& conf)
{
    out << "active_pixel_width=" << conf.active_pixel_width << ' ';
    out << "active_pixel_height=" << conf.active_pixel_height << ' ';
    out << "pixel_array_width=" << conf.pixel_array_width << ' ';
    out << "pixel_array_height=" << conf.pixel_array_height << ' ';
    out << "sensor_timing=" << conf.sensor_timing << ' ';
    out << "output_timing=" << conf.output_timing << ' ';
    out << "fps=" << conf.fps;

    return out;
}


template<typename T, typename Output>
auto cond_set_field(Output& dst, const po::variables_map& vm, const std::string& key) {
    if (vm.count(key)) { dst = vm[key].as<T>(); }
}

auto main(const int argc, const char * const * const argv) -> int
{
    const po::options_description desc = [] {
        po::options_description desc("Options");
        desc.add_options()("active-pixel-width", po::value<std::int16_t>());
        desc.add_options()("active-pixel-height", po::value<std::int16_t>());
        desc.add_options()("pixel-array-width", po::value<std::int16_t>());
        desc.add_options()("pixel-array-height", po::value<std::int16_t>());
        desc.add_options()("sensor-timing", po::value<std::int32_t>());
        desc.add_options()("output-timing", po::value<std::int32_t>());
        desc.add_options()("fps", po::value<float>());
        desc.add_options()("input-file,i", po::value<std::string>());
        desc.add_options()("output-file,o", po::value<std::string>());
        return desc;
    }();

    const po::positional_options_description pdesc = [] {
        po::positional_options_description pdesc;
        pdesc.add("input-file", 1);
        return pdesc;
    }();

    const po::variables_map vm = [&] {
        po::variables_map vm;
        po::store(po::command_line_parser(argc, argv).options(desc).positional(pdesc).run(), vm);
        po::notify(vm);
        return vm;
    }();

    const auto input_filename = [&](const std::string& key) {
        if (vm.count(key)) {
            return vm[key].as<std::string>();
        } else {
            std::cerr << desc << std::endl;
            exit(EXIT_FAILURE);
        }
    }("input-file");

    const configuration conf = [&] {
        std::ifstream input_file(input_filename);
        input_file.exceptions(std::ios::failbit | std::ios::badbit);

        input_file.seekg(conf_offset);
        configuration conf;
        input_file.read(reinterpret_cast<std::fstream::char_type*>(&conf), sizeof(conf));
        return conf;
    }();

    std::cerr << input_filename << ": " << conf << std::endl;

    if (vm.count("output-file")) {
        const configuration new_conf = [&] {
            configuration new_conf = conf;
            cond_set_field<std::int16_t>(new_conf.active_pixel_width, vm, "active-pixel-width");
            cond_set_field<std::int16_t>(new_conf.active_pixel_height, vm, "active-pixel-height");
            cond_set_field<std::int16_t>(new_conf.pixel_array_width, vm, "pixel-array-width");
            cond_set_field<std::int16_t>(new_conf.pixel_array_height, vm, "pixel-array-height");
            cond_set_field<std::int32_t>(new_conf.sensor_timing, vm, "sensor-timing");
            cond_set_field<std::int32_t>(new_conf.output_timing, vm, "output-timing");
            cond_set_field<float>(new_conf.fps, vm, "fps");
            return new_conf;
        }();

        const auto output_filename = vm["output-file"].as<std::string>();
        std::ofstream output_file(output_filename);
        output_file.exceptions(std::ios::failbit | std::ios::badbit);

        [&] {
            std::ifstream input_file(input_filename);
            std::copy(std::istreambuf_iterator<char>(input_file), std::istreambuf_iterator<char>(), std::ostreambuf_iterator<char>(output_file));
        }();

        // The active pixel width and height as 32-bit integer @ wh32_offset needs to be written as well.
        output_file.seekp(wh32_offset);
        const little_int32_t active_pixel_width = static_cast<std::int32_t>(new_conf.active_pixel_width);
        output_file.write(reinterpret_cast<const std::fstream::char_type*>(&active_pixel_width), sizeof(active_pixel_width));
        const little_int32_t active_pixel_height = static_cast<std::int32_t>(new_conf.active_pixel_height);
        output_file.write(reinterpret_cast<const std::fstream::char_type*>(&active_pixel_height), sizeof(active_pixel_height));
        output_file.seekp(conf_offset);
        output_file.write(reinterpret_cast<const std::fstream::char_type*>(&new_conf), sizeof(new_conf));

        std::cerr << output_filename << ": " << new_conf << std::endl;
    }

    return 0;
}

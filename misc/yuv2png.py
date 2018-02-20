from PIL import Image
import sys

def clampint(x, minval, maxval):
    if x < minval:
        return minval
    if x > maxval:
        return maxval
    return int(x)

def main(argv):
    if len(argv) < 4:
        print('Args: [width] [height] [yuv file]', file=sys.stderr)
        sys.exit(1)
    width = int(argv[1])
    height = int(argv[2])
    yuv = open(argv[3], 'rb').read()
    uv_width = width // 2
    uv_height = height // 2
    ubase = width * height
    vbase = width * height + uv_width * uv_height
    im = Image.new('RGB', (width, height))
    index = 0
    for y in range(height):
        uv_row_offset = (y // 2) * uv_width
        for x in range(width):
            yy = yuv[index]
            uv_offset = uv_row_offset + (x // 2)
            uu = yuv[ubase + uv_offset]
            vv = yuv[vbase + uv_offset]
            # https://en.wikipedia.org/wiki/YUV#Y.E2.80.B2UV420sp_.28NV21.29_to_RGB_conversion_.28Android.29
            red = yy + (1.370705 * (vv - 128))
            green = yy - (0.698001 * (vv - 128)) - (0.337633 * (uu - 128))
            blue = yy + (1.732446 * (uu - 128))
            # BGR for some reason
            pixel = clampint(blue, 0, 255) << 16 | clampint(green, 0, 255) << 8 | clampint(red, 0, 255)
            im.putpixel((x, y), pixel)
            index += 1
    im.show()

if __name__ == '__main__':
    main(sys.argv)
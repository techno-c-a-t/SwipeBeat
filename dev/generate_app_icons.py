import os
import struct
import zlib

def make_chunk(chunk_type, data):
    length = len(data)
    crc = zlib.crc32(chunk_type + data) & 0xFFFFFFFF
    return struct.pack('>I', length) + chunk_type + data + struct.pack('>I', crc)

def read_png_rgba(image_path):
    with open(image_path, 'rb') as f:
        png_data = f.read()

    if png_data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError("File is not a valid PNG")

    idx = 8
    ihdr_data = b''
    idat_data = b''

    while idx < len(png_data):
        length, = struct.unpack('>I', png_data[idx:idx+4])
        chunk_type = png_data[idx+4:idx+8]
        chunk_data = png_data[idx+8:idx+8+length]
        if chunk_type == b'IHDR': ihdr_data = chunk_data
        elif chunk_type == b'IDAT': idat_data += chunk_data
        elif chunk_type == b'IEND': break
        idx += 12 + length

    width, height, bit_depth, color_type, _, _, _ = struct.unpack('>IIBBBBB', ihdr_data)
    src_bytes_per_pixel = 4 if color_type == 6 else (3 if color_type == 2 else 4)

    decompressed = zlib.decompress(idat_data)
    stride = width * src_bytes_per_pixel
    pixels_raw = bytearray(height * stride)

    def paeth(a, b, c):
        p = a + b - c
        pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
        return a if pa <= pb and pa <= pc else (b if pb <= pc else c)

    curr_idx, raw_idx = 0, 0
    for y in range(height):
        filter_type = decompressed[raw_idx]
        raw_idx += 1
        for x in range(stride):
            x_raw = decompressed[raw_idx]
            raw_idx += 1
            a = pixels_raw[curr_idx - src_bytes_per_pixel] if x >= src_bytes_per_pixel else 0
            b = pixels_raw[curr_idx - stride] if y > 0 else 0
            c = pixels_raw[curr_idx - stride - src_bytes_per_pixel] if (y > 0 and x >= src_bytes_per_pixel) else 0

            if filter_type == 0: val = x_raw
            elif filter_type == 1: val = (x_raw + a) & 0xFF
            elif filter_type == 2: val = (x_raw + b) & 0xFF
            elif filter_type == 3: val = (x_raw + (a + b) // 2) & 0xFF
            elif filter_type == 4: val = (x_raw + paeth(a, b, c)) & 0xFF
            else: val = x_raw
            pixels_raw[curr_idx] = val
            curr_idx += 1

    rgba = bytearray(width * height * 4)
    out_idx = 0
    for y in range(height):
        for x in range(width):
            in_idx = y * stride + x * src_bytes_per_pixel
            r = pixels_raw[in_idx]
            g = pixels_raw[in_idx + 1]
            b = pixels_raw[in_idx + 2]
            a = pixels_raw[in_idx + 3] if src_bytes_per_pixel == 4 else 255
            rgba[out_idx] = r
            rgba[out_idx + 1] = g
            rgba[out_idx + 2] = b
            rgba[out_idx + 3] = a
            out_idx += 4

    return width, height, rgba

def resize_rgba_bilinear(src_rgba, src_w, src_h, dst_w, dst_h):
    dst_rgba = bytearray(dst_w * dst_h * 4)

    for y in range(dst_h):
        src_y = int(y * (src_h / dst_h))
        for x in range(dst_w):
            src_x = int(x * (src_w / dst_w))

            src_idx = (src_y * src_w + src_x) * 4
            dst_idx = (y * dst_w + x) * 4

            dst_rgba[dst_idx] = src_rgba[src_idx]
            dst_rgba[dst_idx + 1] = src_rgba[src_idx + 1]
            dst_rgba[dst_idx + 2] = src_rgba[src_idx + 2]
            dst_rgba[dst_idx + 3] = src_rgba[src_idx + 3]

    return dst_rgba

def create_adaptive_foreground(src_rgba, src_w, src_h, canvas_size=432):
    # Adaptive foreground canvas is 432x432 with avatar scaled to inner 288x288 safe zone
    canvas = bytearray(canvas_size * canvas_size * 4)
    inner_size = int(canvas_size * 0.66) # 288px
    offset = (canvas_size - inner_size) // 2 # 72px margin

    inner_resized = resize_rgba_bilinear(src_rgba, src_w, src_h, inner_size, inner_size)

    for iy in range(inner_size):
        cy = offset + iy
        for ix in range(inner_size):
            cx = offset + ix
            src_idx = (iy * inner_size + ix) * 4
            dst_idx = (cy * canvas_size + cx) * 4

            canvas[dst_idx] = inner_resized[src_idx]
            canvas[dst_idx + 1] = inner_resized[src_idx + 1]
            canvas[dst_idx + 2] = inner_resized[src_idx + 2]
            canvas[dst_idx + 3] = inner_resized[src_idx + 3]

    return canvas

def save_png_rgba(filename, width, height, rgba_data):
    os.makedirs(os.path.dirname(filename), exist_ok=True)
    raw_pixels = bytearray()
    stride = width * 4

    for y in range(height):
        raw_pixels.append(0)
        start = y * stride
        raw_pixels.extend(rgba_data[start:start + stride])

    ihdr = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(raw_pixels, level=6)

    with open(filename, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(make_chunk(b'IHDR', ihdr))
        f.write(make_chunk(b'IDAT', idat))
        f.write(make_chunk(b'IEND', b''))

def generate_all_icons(avatar_path, res_dir):
    print(f"Loading avatar from {avatar_path}...")
    src_w, src_h, src_rgba = read_png_rgba(avatar_path)
    print(f"Loaded {src_w}x{src_h} image.")

    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    for folder, size in densities.items():
        dst_folder = os.path.join(res_dir, folder)
        resized = resize_rgba_bilinear(src_rgba, src_w, src_h, size, size)

        icon_path = os.path.join(dst_folder, "ic_launcher.png")
        round_icon_path = os.path.join(dst_folder, "ic_launcher_round.png")

        save_png_rgba(icon_path, size, size, resized)
        save_png_rgba(round_icon_path, size, size, resized)
        print(f"Generated {size}x{size} icons in {folder}.")

    # Generate adaptive foreground with safe inner padding
    fg_canvas = create_adaptive_foreground(src_rgba, src_w, src_h, canvas_size=432)
    fg_path = os.path.join(res_dir, "drawable", "ic_launcher_foreground.png")
    save_png_rgba(fg_path, 432, 432, fg_canvas)
    print("Generated adaptive icon foreground ic_launcher_foreground.png (432x432 with safe margins).")

if __name__ == "__main__":
    dev_dir = os.path.dirname(os.path.abspath(__file__))
    project_dir = os.path.dirname(dev_dir)
    avatar_file = os.path.join(dev_dir, "avatar.png")
    res_directory = os.path.join(project_dir, "app", "src", "main", "res")

    generate_all_icons(avatar_file, res_directory)
    print("Done! App icons updated successfully across all densities.")

import os
import struct
import zlib

def crop_png_strict_square(image_path, output_path):
    if not os.path.exists(image_path):
        print(f"Ошибка: Файл '{image_path}' не найден.")
        return

    with open(image_path, 'rb') as f:
        png_data = f.read()

    if png_data[:8] != b'\x89PNG\r\n\x1a\n':
        print("Ошибка: Файл не является валидным PNG.")
        return

    idx = 8
    ihdr_data = b''
    idat_data = b''

    while idx < len(png_data):
        # Исправлено: распаковываем кортеж, чтобы получить чистое число int
        length, = struct.unpack('>I', png_data[idx:idx+4])
        chunk_type = png_data[idx+4:idx+8]
        chunk_data = png_data[idx+8:idx+8+length]
        if chunk_type == b'IHDR': ihdr_data = chunk_data
        elif chunk_type == b'IDAT': idat_data += chunk_data
        elif chunk_type == b'IEND': break
        idx += 12 + length

    width, height, bit_depth, color_type, _, _, _ = struct.unpack('>IIBBBBB', ihdr_data)

    bytes_per_pixel = 4 if color_type == 6 else 3
    stride = width * bytes_per_pixel
    decompressed = zlib.decompress(idat_data)
    pixels = bytearray(height * stride)

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
            a = pixels[curr_idx - bytes_per_pixel] if x >= bytes_per_pixel else 0
            b = pixels[curr_idx - stride] if y > 0 else 0
            c = pixels[curr_idx - stride - bytes_per_pixel] if (y > 0 and x >= bytes_per_pixel) else 0

            if filter_type == 0: val = x_raw
            elif filter_type == 1: val = (x_raw + a) & 0xFF
            elif filter_type == 2: val = (x_raw + b) & 0xFF
            elif filter_type == 3: val = (x_raw + (a + b) // 2) & 0xFF
            elif filter_type == 4: val = (x_raw + paeth(a, b, c)) & 0xFF
            else: val = x_raw
            pixels[curr_idx] = val
            curr_idx += 1

    # Порог для отсечения сглаженных пикселей фона
    bg_r, bg_g, bg_b = pixels[0], pixels[1], pixels[2]
    tolerance = 80 

    left, top, right, bottom = width, height, 0, 0
    found = False

    for y in range(height):
        row_offset = y * stride
        for x in range(width):
            p_idx = row_offset + x * bytes_per_pixel
            r, g, b = pixels[p_idx], pixels[p_idx+1], pixels[p_idx+2]

            is_bg = (abs(r - bg_r) < tolerance and 
                     abs(g - bg_g) < tolerance and 
                     abs(b - bg_b) < tolerance)

            if not is_bg:
                found = True
                if x < left: left = x
                if y < top: top = y
                if x > right: right = x
                if y > bottom: bottom = y

    if not found:
        print("Объект не найден.")
        return

    # Небольшой внутренний отступ для гарантированного удаления белой каймы
    padding = 4
    left += padding
    top += padding
    right -= padding
    bottom -= padding

    obj_w = right - left + 1
    obj_h = bottom - top + 1

    # Ориентируемся строго по минимальной стороне (высоте), чтобы срезать все белые поля
    min_side = min(obj_w, obj_h)

    # Ищем центр исходной черной фигуры
    center_x = left + obj_w // 2
    center_y = top + obj_h // 2

    # Пересчитываем новые квадратные координаты
    final_left = center_x - min_side // 2
    final_top = center_y - min_side // 2
    
    # Защита от выхода за физический холст картинки
    final_left = max(0, min(width - min_side, final_left))
    final_top = max(0, min(height - min_side, final_top))
    
    new_size = min_side

    # Собираем чистый PNG обратно
    new_stride = new_size * bytes_per_pixel
    new_pixels = bytearray()

    for y in range(final_top, final_top + new_size):
        new_pixels.append(0)
        row_offset = y * stride + final_left * bytes_per_pixel
        new_pixels.extend(pixels[row_offset : row_offset + new_stride])

    new_ihdr = struct.pack('>IIBBBBB', new_size, new_size, bit_depth, color_type, 0, 0, 0)
    new_idat = zlib.compress(new_pixels, level=6)

    def make_chunk(chunk_type, data):
        return struct.pack('>I', len(data)) + chunk_type + data + struct.pack('>I', zlib.crc32(chunk_type + data))

    with open(output_path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(make_chunk(b'IHDR', new_ihdr))
        f.write(make_chunk(b'IDAT', new_idat))
        f.write(make_chunk(b'IEND', b''))

    print(f"Готово! Иконка строго обрезана в квадрат. Итоговый чистый размер: {new_size}x{new_size}px.")

if __name__ == "__main__":
    crop_png_strict_square("icon.png", "cropped_icon.png")

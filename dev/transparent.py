import os
import struct
import zlib
from collections import deque

def convert_bg_to_transparent_bfs(image_path, output_path):
    if not os.path.exists(image_path):
        print(f"Ошибка: Входной файл '{image_path}' не найден.")
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
        length, = struct.unpack('>I', png_data[idx:idx+4])
        chunk_type = png_data[idx+4:idx+8]
        chunk_data = png_data[idx+8:idx+8+length]
        if chunk_type == b'IHDR': ihdr_data = chunk_data
        elif chunk_type == b'IDAT': idat_data += chunk_data
        elif chunk_type == b'IEND': break
        idx += 12 + length

    width, height, bit_depth, color_type, _, _, _ = struct.unpack('>IIBBBBB', ihdr_data)

    src_bytes_per_pixel = 4 if color_type == 6 else 3
    stride = width * src_bytes_per_pixel
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
            a = pixels[curr_idx - src_bytes_per_pixel] if x >= src_bytes_per_pixel else 0
            b = pixels[curr_idx - stride] if y > 0 else 0
            # ИСПРАВЛЕНО: заменено bytes_per_pixel на src_bytes_per_pixel
            c = pixels[curr_idx - stride - src_bytes_per_pixel] if (y > 0 and x >= src_bytes_per_pixel) else 0

            if filter_type == 0: val = x_raw
            elif filter_type == 1: val = (x_raw + a) & 0xFF
            elif filter_type == 2: val = (x_raw + b) & 0xFF
            elif filter_type == 3: val = (x_raw + (a + b) // 2) & 0xFF
            elif filter_type == 4: val = (x_raw + paeth(a, b, c)) & 0xFF
            else: val = x_raw
            pixels[curr_idx] = val
            curr_idx += 1

    # Порог определения "не черного"
    # Позволяет волне BFS сожрать всю кайму сглаживания вплоть до плотного черного тела
    color_threshold = 20

    # Инициализируем очередь для BFS и матрицу посещенных пикселей
    queue = deque()
    visited = [False] * (width * height)

    def is_light_pixel(px, py):
        p_idx = py * stride + px * src_bytes_per_pixel
        return pixels[p_idx] > color_threshold and pixels[p_idx+1] > color_threshold and pixels[p_idx+2] > color_threshold

    # Заносим углы
    corners = [(0, 0), (width - 1, 0), (0, height - 1), (width - 1, height - 1)]
    for cx, cy in corners:
        if is_light_pixel(cx, cy):
            queue.append((cx, cy))
            visited[cy * width + cx] = True

    directions = [(-1, 0), (1, 0), (0, -1), (0, 1)]
    
    while queue:
        cx, cy = queue.popleft()
        for dx, dy in directions:
            nx, ny = cx + dx, cy + dy
            if 0 <= nx < width and 0 <= ny < height:
                idx_flat = ny * width + nx
                if not visited[idx_flat]:
                    if is_light_pixel(nx, ny):
                        visited[idx_flat] = True
                        queue.append((nx, ny))

    # Сборка итогового RGBA изображения
    new_pixels = bytearray()

    for y in range(height):
        new_pixels.append(0)  # Filter type None
        row_offset = y * stride
        
        for x in range(width):
            p_idx = row_offset + x * src_bytes_per_pixel
            r, g, b = pixels[p_idx], pixels[p_idx+1], pixels[p_idx+2]
            
            # ИСПРАВЛЕНО: теперь корректно вставляются нули для прозрачности
            if visited[y * width + x]:
                new_pixels.extend([0, 0, 0, 0])
            else:
                new_pixels.extend([r, g, b, 255])

    new_ihdr = struct.pack('>IIBBBBB', width, height, bit_depth, 6, 0, 0, 0)
    new_idat = zlib.compress(new_pixels, level=6)

    def make_chunk(chunk_type, data):
        return struct.pack('>I', len(data)) + chunk_type + data + struct.pack('>I', zlib.crc32(chunk_type + data))

    with open(output_path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(make_chunk(b'IHDR', new_ihdr))
        f.write(make_chunk(b'IDAT', new_idat))
        f.write(make_chunk(b'IEND', b''))

    print(f"Готово! Внешний фон вырезан волной BFS. Результат сохранен в '{output_path}'")

if __name__ == "__main__":
    convert_bg_to_transparent_bfs("cropped_icon.png", "avatar.png")

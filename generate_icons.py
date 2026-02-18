#!/usr/bin/env python3
from PIL import Image, ImageDraw, ImageFont
import os

def create_icon(size, output_path):
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    scale = size / 192
    
    def scale_val(v):
        return max(1, int(v * scale))
    
    def scale_coords(coords):
        if isinstance(coords, tuple):
            return tuple(int(c * scale) for c in coords)
        return [int(c * scale) for c in coords]
    
    center = size // 2
    radius = scale_val(90)
    for r in range(radius, 0, -1):
        ratio = r / radius
        blue = int(115 + (71 - 115) * (1 - ratio))
        green = int(115 + (71 - 115) * (1 - ratio))
        red = int(26 + (13 - 26) * (1 - ratio))
        draw.ellipse([center - r, center - r, center + r, center + r], 
                     fill=(red, green, blue, 255))
    
    shield_points = [
        (96, 28), (152, 52), (152, 100), (125, 145), (96, 168),
        (67, 145), (40, 100), (40, 52)
    ]
    scaled_shield = [scale_coords(p) for p in shield_points]
    draw.polygon(scaled_shield, fill=(79, 195, 247, 230))
    
    cam_x, cam_y = scale_val(60), scale_val(70)
    cam_w, cam_h = scale_val(50), scale_val(38)
    cam_r = scale_val(6)
    draw.rounded_rectangle([cam_x, cam_y, cam_x + cam_w, cam_y + cam_h], 
                           radius=cam_r, fill=(255, 255, 255, 255))
    
    lens_cx, lens_cy = scale_val(85), scale_val(89)
    lens_r = scale_val(10)
    draw.ellipse([lens_cx - lens_r, lens_cy - lens_r, lens_cx + lens_r, lens_cy + lens_r],
                 fill=(26, 115, 232, 255))
    inner_r = scale_val(5)
    draw.ellipse([lens_cx - inner_r, lens_cy - inner_r, lens_cx + inner_r, lens_cy + inner_r],
                 fill=(255, 255, 255, 255))
    
    poly_points = [(110, 78), (130, 68), (130, 110), (110, 100)]
    scaled_poly = [scale_coords(p) for p in poly_points]
    draw.polygon(scaled_poly, fill=(255, 255, 255, 255))
    
    ai_cx, ai_cy = scale_val(130), scale_val(140)
    ai_r = scale_val(18)
    draw.ellipse([ai_cx - ai_r, ai_cy - ai_r, ai_cx + ai_r, ai_cy + ai_r],
                 fill=(255, 152, 0, 255))
    
    ai_font_size = max(8, scale_val(14))
    try:
        ai_font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", ai_font_size)
    except:
        ai_font = ImageFont.load_default()
    
    bbox = draw.textbbox((0, 0), "AI", font=ai_font)
    text_w = bbox[2] - bbox[0]
    text_h = bbox[3] - bbox[1]
    draw.text((ai_cx - text_w // 2, ai_cy - text_h // 2 - 1), "AI", fill=(255, 255, 255, 255), font=ai_font)
    
    free_x, free_y = scale_val(20), scale_val(145)
    free_w, free_h = scale_val(45), scale_val(22)
    free_r = scale_val(4)
    draw.rounded_rectangle([free_x, free_y, free_x + free_w, free_y + free_h],
                           radius=free_r, fill=(76, 175, 80, 255))
    
    free_font_size = max(6, scale_val(12))
    try:
        free_font = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", free_font_size)
    except:
        try:
            free_font = ImageFont.truetype("/System/Library/Fonts/STHeiti Light.ttc", free_font_size)
        except:
            free_font = ImageFont.load_default()
    
    bbox = draw.textbbox((0, 0), "免费", font=free_font)
    text_w = bbox[2] - bbox[0]
    text_h = bbox[3] - bbox[1]
    draw.text((free_x + (free_w - text_w) // 2, free_y + (free_h - text_h) // 2 - 1), 
              "免费", fill=(255, 255, 255, 255), font=free_font)
    
    img.save(output_path, 'PNG')
    print(f"Created: {output_path}")

sizes = [
    (48, 'mipmap-mdpi'),
    (72, 'mipmap-hdpi'),
    (96, 'mipmap-xhdpi'),
    (144, 'mipmap-xxhdpi'),
    (192, 'mipmap-xxxhdpi'),
]

base_path = '/Users/pipiqiang/code/QNVR/app/src/main/res'
for size, folder in sizes:
    output_path = os.path.join(base_path, folder, 'ic_launcher.png')
    create_icon(size, output_path)
    round_path = os.path.join(base_path, folder, 'ic_launcher_round.png')
    create_icon(size, round_path)

print("\nAll icons generated successfully!")

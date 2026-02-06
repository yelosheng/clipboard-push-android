import os
from PIL import Image
from pathlib import Path

# Source Icon Path (from previous session artifact)
SOURCE_ICON_PATH = r"C:\Users\Huang\.gemini\antigravity\brain\0fc6e0b2-f87b-4093-a60a-5e5942451887\media__1770339190784.png"
RES_DIR = r"d:\android-dev\clipboard-man\app\src\main\res"

ICON_CONFIGS = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]

def generate_icons():
    if not os.path.exists(SOURCE_ICON_PATH):
        print(f"Error: Source file not found: {SOURCE_ICON_PATH}")
        return

    try:
        img = Image.open(SOURCE_ICON_PATH)
        print(f"Loaded source image: {img.size}")

        for folder, size in ICON_CONFIGS:
            out_dir = os.path.join(RES_DIR, folder)
            os.makedirs(out_dir, exist_ok=True)
            
            # Resize
            resized_img = img.resize((size, size), Image.Resampling.LANCZOS)
            
            # Save standard name
            out_path = os.path.join(out_dir, "ic_launcher.png")
            resized_img.save(out_path)
            
            # Save round name (using same image for now, ideally should be masked)
            out_path_round = os.path.join(out_dir, "ic_launcher_round.png")
            resized_img.save(out_path_round)

            print(f"Generated {folder}: {size}x{size}")

        print("✅ Icons generated successfully")

    except Exception as e:
        print(f"❌ Error generating icons: {e}")

if __name__ == "__main__":
    generate_icons()

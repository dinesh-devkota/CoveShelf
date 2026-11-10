"""Regenerate tiny synthetic photo fixtures (requires Pillow; not needed by CI)."""

from pathlib import Path

from PIL import Image, ImageDraw


def main() -> None:
    destination = Path(__file__).resolve().parents[1] / "CineVaultTests" / "Fixtures" / "Photos"
    destination.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGB", (96, 64), (24, 49, 74))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 47, 31), fill=(225, 55, 65))
    draw.rectangle((48, 0, 95, 31), fill=(45, 180, 95))
    draw.rectangle((0, 32, 47, 63), fill=(50, 110, 220))
    draw.rectangle((48, 32, 95, 63), fill=(235, 195, 65))
    image.save(destination / "01.jpg", quality=95, subsampling=0)
    image.save(destination / "02.png")
    image.save(destination / "03.webp", lossless=True)
    orientation = Image.Exif()
    orientation[274] = 6  # Rotate clockwise: encoded 96 x 64, displayed 64 x 96.
    image.save(destination / "portrait.jpg", quality=95, subsampling=0, exif=orientation)


if __name__ == "__main__":
    main()

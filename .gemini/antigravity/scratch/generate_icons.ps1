Add-Type -AssemblyName System.Drawing
$source = "H:\Desktop\widgets\24_hr_clock_android\clock_icon_safe.png"
$resDir = "c:\Users\user1\AndroidStudioProjects\24_hr_clock\app\src\main\res"

function Resize-Image($src, $dst, $width, $height) {
    try {
        $img = [System.Drawing.Image]::FromFile($src)
        $newImg = New-Object System.Drawing.Bitmap($width, $height)
        $g = [System.Drawing.Graphics]::FromImage($newImg)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.DrawImage($img, 0, 0, $width, $height)
        
        # Ensure directory exists
        $dir = [System.IO.Path]::GetDirectoryName($dst)
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir }
        
        $newImg.Save($dst, [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose()
        $newImg.Dispose()
        $img.Dispose()
        Write-Host "Created $dst"
    } catch {
        Write-Error "Failed to create $dst : $($_.Exception.Message)"
    }
}

# Standard Launcher Icons
Resize-Image $source "$resDir\mipmap-mdpi\ic_launcher.png" 48 48
Resize-Image $source "$resDir\mipmap-hdpi\ic_launcher.png" 72 72
Resize-Image $source "$resDir\mipmap-xhdpi\ic_launcher.png" 96 96
Resize-Image $source "$resDir\mipmap-xxhdpi\ic_launcher.png" 144 144
Resize-Image $source "$resDir\mipmap-xxxhdpi\ic_launcher.png" 192 192

# Round Icons
Resize-Image $source "$resDir\mipmap-mdpi\ic_launcher_round.png" 48 48
Resize-Image $source "$resDir\mipmap-hdpi\ic_launcher_round.png" 72 72
Resize-Image $source "$resDir\mipmap-xhdpi\ic_launcher_round.png" 96 96
Resize-Image $source "$resDir\mipmap-xxhdpi\ic_launcher_round.png" 144 144
Resize-Image $source "$resDir\mipmap-xxxhdpi\ic_launcher_round.png" 192 192

# For Adaptive Icon foreground (108x108)
Resize-Image $source "$resDir\drawable\ic_launcher_foreground_bitmap.png" 108 108

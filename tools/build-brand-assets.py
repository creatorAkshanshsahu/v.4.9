"""Create Android-size derivatives of the user-selected AK TV mark (no regeneration)."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root=Path(__file__).resolve().parents[1]
source=Image.open(root/'design/ak-tv-logo.png').convert('RGBA')
pixels=source.load()
mask=Image.new('L',source.size); alpha=mask.load()
for y in range(source.height):
    for x in range(source.width):
        r,g,b,a=pixels[x,y]
        strength=max(g,b)
        # Remove only the near-black presentation backdrop; preserve the selected cyan geometry.
        alpha[x,y]=int(max(0,min(255,(strength-48)*255/72))) if g-r>30 or b-r>30 else 0
source.putalpha(mask)
bounds=mask.point(lambda v:255 if v>180 else 0).getbbox()
source=source.crop((max(0,bounds[0]-18),max(0,bounds[1]-18),min(source.width,bounds[2]+18),min(source.height,bounds[3]+18)))
mark=source.resize((512,round(512*source.height/source.width)),Image.Resampling.LANCZOS)
res=root/'app/src/main/res/drawable-nodpi'
res.mkdir(parents=True,exist_ok=True)
for name in ('ic_mark','ic_mark_blue','ic_intro'):
    mark.save(res/(name+'.png'),optimize=True)
mark.save(root/'design/ak-tv-mark-transparent.png',optimize=True)
icon=Image.new('RGBA',(512,512),(8,13,20,255))
small=mark.resize((372,round(372*mark.height/mark.width)),Image.Resampling.LANCZOS)
icon.alpha_composite(small,((512-small.width)//2,(512-small.height)//2))
icon.convert('RGB').save(res/'ic_logo.png',optimize=True)
foreground=Image.new('RGBA',(432,432))
small=mark.resize((264,round(264*mark.height/mark.width)),Image.Resampling.LANCZOS)
foreground.alpha_composite(small,((432-small.width)//2,(432-small.height)//2))
foreground.save(res/'ic_launcher_foreground.png',optimize=True)
fontdir=root/'app/src/main/res/font'
banner=Image.new('RGB',(640,360),(8,13,20))
small=mark.resize((228,round(228*mark.height/mark.width)),Image.Resampling.LANCZOS)
banner.paste(small,(42,(360-small.height)//2),small)
draw=ImageDraw.Draw(banner)
font=ImageFont.truetype(str(fontdir/'sora_semibold.ttf'),64)
draw.text((310,117),'AK TV',font=font,fill=(239,248,248))
font2=ImageFont.truetype(str(fontdir/'sora_regular.ttf'),17)
draw.text((314,207),'LIVE. FILMS. YOUR WORLD.',font=font2,fill=(132,169,179))
banner.resize((320,180),Image.Resampling.LANCZOS).save(res/'banner_img.png',optimize=True)
banner.save(root/'design/ak-tv-banner.png',optimize=True)
print('Brand assets:',source.size,'->',mark.size)

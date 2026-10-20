"""Motion preview of the native 1.6 s entrance, using the selected mark and native UI test image."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math

root=Path(__file__).resolve().parents[1]
mark=Image.open(root/'design/ak-tv-mark-transparent.png').convert('RGBA')
home=Image.open(root/'design/previews/home-tv.webp').convert('RGBA').resize((960,540),Image.Resampling.LANCZOS)
fontdir=root/'app/src/main/res/font'
title=ImageFont.truetype(str(fontdir/'sora_semibold.ttf'),23)
subtitle=ImageFont.truetype(str(fontdir/'sora_semibold.ttf'),9)
hint=ImageFont.truetype(str(fontdir/'sora_regular.ttf'),9)
frames=[]
for frame in range(41):
    milliseconds=frame*40
    t=min(1,milliseconds/1600)
    progress=1-(1-t)**2.5
    leave=max(0,min(1,(progress-.93)/.07))
    alpha=round(255*(1-leave))
    appear=min(1,progress/.32)
    canvas=home.copy() if milliseconds>=1050 else Image.new('RGBA',(960,540),(8,13,20,255))
    overlay=Image.new('RGBA',canvas.size,(8,13,20,alpha))
    canvas=Image.alpha_composite(canvas,overlay)
    mw=round(min(960*.26,240)*(.94+.06*appear)); mh=round(mw*mark.height/mark.width)
    logo=mark.resize((mw,mh),Image.Resampling.LANCZOS)
    logo.putalpha(logo.getchannel('A').point(lambda v:round(v*alpha/255*appear)))
    cy=540*.4+(1-appear)*8
    x=round(480-mw/2); y=round(cy-mh/2); bottom=cy+mh/2
    canvas.alpha_composite(logo,(x,y))
    layer=Image.new('RGBA',canvas.size)
    draw=ImageDraw.Draw(layer)
    draw.text((480,bottom+34),'AK TV',font=title,anchor='ms',fill=(239,246,250,round(alpha*appear)))
    subalpha=round(alpha*max(0,min(1,(progress-.22)/.35)))
    draw.text((480,bottom+57),'LIVE. FILMS. YOUR WORLD.',font=subtitle,anchor='ms',fill=(166,183,198,subalpha))
    span=48*max(0,min(1,(progress-.18)/.6))
    draw.line((480-span,bottom+78,480+span,bottom+78),fill=(50,229,206,round(alpha*.65)),width=2)
    draw.text((480,518),'Press any key or tap to skip',font=hint,anchor='ms',fill=(109,130,150,round(alpha*.75)))
    canvas=Image.alpha_composite(canvas,layer).convert('RGB').resize((720,405),Image.Resampling.LANCZOS)
    frames.append(canvas)
# A short hold on the home design makes the reveal readable; actual app is immediately interactive.
frames.extend([frames[-1]]*20)
frames[0].save(root/'design/entrance-preview.gif',save_all=True,append_images=frames[1:],duration=40,loop=0,optimize=True)
print('Wrote design/entrance-preview.gif (motion illustration, not a device recording)')

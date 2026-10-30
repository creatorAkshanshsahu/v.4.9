#!/usr/bin/env python3
"""Create labelled static AK TV UI direction boards; these are concepts, not app screenshots."""
from pathlib import Path
import html

OUT = Path(__file__).resolve().parents[1] / "design" / "previews" / "directions"
OUT.mkdir(parents=True, exist_ok=True)
W, H = 960, 540


def txt(x, y, value, size=14, color="#eef7fb", weight=500, extra=""):
    return f'<text x="{x}" y="{y}" fill="{color}" font-family="DejaVu Sans, sans-serif" font-size="{size}" font-weight="{weight}" {extra}>{html.escape(value)}</text>'


def rect(x, y, w, h, fill, rx=0, extra=""):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" {extra}/>'


def svg_shell(title, num, defs, body):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
<defs>{defs}</defs>{rect(0,0,W,H,'#070c14')}<g>{body}</g>
{rect(0,0,W,34,'#101923')}<circle cx="20" cy="17" r="5" fill="#32e5ce"/>{txt(34,22,f'DESIGN PREVIEW  /  {num}  ·  {title.upper()}',11,'#b8c7ce',700,'letter-spacing="1.2"')}{txt(748,22,'AK TV  •  STATIC CONCEPT',10,'#78909a',600,'letter-spacing="1"')}
</svg>'''


def artwork(x, y, w, h, variant, radius=12):
    # Custom, abstract poster art; no film stills or external assets.
    palette = [('#153940','#51d6c4','#071924'), ('#25314d','#9291d9','#090f21'), ('#52352b','#efaa72','#17121a'), ('#153c4b','#72cee1','#07151e')][variant % 4]
    gid = f'art{variant}'
    shape = f'''<linearGradient id="{gid}" x1="0" y1="0" x2="1" y2="1"><stop stop-color="{palette[0]}"/><stop offset=".56" stop-color="{palette[1]}"/><stop offset="1" stop-color="{palette[2]}"/></linearGradient>'''
    # caller injects gradients below via a shared gradient subset
    art = rect(x,y,w,h,f'url(#{gid})',radius)
    art += f'<circle cx="{x+w*.72:.0f}" cy="{y+h*.35:.0f}" r="{min(w,h)*.20:.0f}" fill="#ffffff" opacity=".13"/>'
    art += f'<path d="M{x} {y+h*.83:.0f} Q{x+w*.3:.0f} {y+h*.5:.0f} {x+w*.54:.0f} {y+h*.8:.0f} T{x+w:.0f} {y+h*.62:.0f} V{y+h:.0f} H{x}Z" fill="#06101a" opacity=".74"/>'
    art += txt(x+12,y+h-17,['ORBIT','NORTH','EMBER','DEEP BLUE'][variant%4], max(10,int(w*.105)), '#f4fbfc', 800, 'letter-spacing="1.2"')
    return shape, art


def nav(x=24, selected='Live'):
    labels = [('◉','Live'),('↻','Always on'),('▣','Movies'),('▤','Series')]
    s = ''
    for i,(icon,label) in enumerate(labels):
        y=126+i*76
        if label == selected: s += rect(x-12,y-27,90,56,'#123b3b',14,'stroke="#32e5ce" stroke-width="2"')
        s += txt(x,y,icon,20,'#32e5ce' if label==selected else '#9aaab2',700)
        s += txt(x-4,y+21,label,10,'#eaf7f6' if label==selected else '#a0adb4',600)
    return s


def direction_rail():
    defs='''<linearGradient id="hero" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#142d37"/><stop offset="1" stop-color="#0b1724"/></linearGradient>'''
    defs += ''.join(artwork(0,0,1,1,i)[0] for i in range(4))
    b=rect(0,34,104,506,'#0d151f')+txt(22,77,'AK',28,'#32e5ce',900)+txt(24,96,'TV',10,'#d7e7e9',700,'letter-spacing="2"')+nav(34)
    b+=txt(130,76,'TONIGHT, ON AK TV',10,'#32e5ce',700,'letter-spacing="2"')+txt(130,111,'Live TV',29,'#f2f7f9',800)
    b+=rect(126,134,805,236,'url(#hero)',18)
    b+=txt(156,174,'●  LIVE NOW    /    SPORTS',11,'#32e5ce',700,'letter-spacing="1"')
    b+=txt(156,219,'Star Sports 1 Hindi HD',29,'#f2f7f9',800)
    b+=txt(156,245,'India vs Australia  ·  1st ODI',14,'#c0cbd0',500)
    b+=rect(156,270,137,42,'#32e5ce',22)+txt(178,296,'▶  WATCH LIVE',12,'#052128',800,'letter-spacing=".5"')
    b+=txt(318,296,'Guide  ·  Favorites',12,'#c1d1d5',600)
    b+=txt(790,194,'AK',66,'#32e5ce',900,'opacity=".92"')
    b+=txt(129,408,'YOUR CHANNELS',12,'#eaf4f7',700,'letter-spacing="1"')
    for i,(name,sub) in enumerate([('Sony YAY','Animation'),('Zee Cinema','Movies'),('News18 India','News'),('Star Sports','Sports')]):
        x=130+i*193
        b+=rect(x,424,178,83,'#15242d',12,'stroke="#31464d" stroke-width="1"')
        b+=txt(x+14,454,name,14,'#eef7f8',700)+txt(x+14,481,sub,10,'#97aab0',500)
        b+=rect(x+142,438,24,14,'#cf4862',5)+txt(x+147,449,'LIVE',8,'#fff',700)
    return svg_shell('Rail-led cinematic', '01', defs,b)


def direction_editorial():
    defs='''<linearGradient id="feature" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#17363d"/><stop offset="1" stop-color="#101a2a"/></linearGradient>'''
    defs += ''.join(artwork(0,0,1,1,i)[0] for i in range(4))
    b=rect(0,34,74,506,'#0c151e')+txt(18,77,'AK',25,'#32e5ce',900)
    # Compact nav rail strips
    for yy,ic in [(134,'◉'),(190,'↻'),(246,'▣'),(302,'▤')]: b+=txt(25,yy,ic,21,'#32e5ce' if yy==134 else '#82949d',700)
    b+=txt(103,78,'LIVE TV   /   PICKS FOR YOU',10,'#32e5ce',700,'letter-spacing="1.4"')
    b+=txt(103,119,'The front row',31,'#f5f8fa',800)
    b+=txt(805,111,'TUESDAY  ·  8:45 PM',10,'#98a9b0',600,'letter-spacing="1"')
    b+=rect(103,144,824,113,'url(#feature)',14)
    b+=txt(128,178,'LIVE  ·  SPORTS / HINDI',10,'#32e5ce',700,'letter-spacing="1"')
    b+=txt(128,212,'Star Sports 1 Hindi HD',22,'#f3f8fa',800)
    b+=txt(128,237,'India vs Australia  ·  Starts now',12,'#bdcbd0',500)
    b+=rect(800,178,96,42,'#32e5ce',21)+txt(818,204,'WATCH  ▶',11,'#06252b',800)
    b+=txt(103,291,'ON AIR NOW',11,'#f2f6f8',800,'letter-spacing="1.2"')+txt(805,291,'VIEW ALL  →',10,'#32e5ce',700)
    for i,(title,caption) in enumerate([('Star Sports 1','India vs Australia'),('Sony YAY','Miraculous'),('Zee Cinema','Special 26'),('News18','Top headlines')]):
        x=103+i*204
        shp,art=artwork(x,308,185,105,i)
        b+=art+rect(x,380,185,33,'#081018',0,'opacity=".79"')+txt(x+10,401,title,12,'#f3f8f9',700)
        b+=txt(x,433,caption,10,'#aab9bf',500)
    b+=txt(103,477,'UP NEXT',11,'#f2f6f8',800,'letter-spacing="1.2"')
    for i,(t,c) in enumerate([('Zee Cinema  ·  9:00 PM','Premiere'),('Colors  ·  9:30 PM','Drama')]):
        x=103+i*407
        b+=rect(x,490,390,34,'#121f29',8)+txt(x+12,512,t,11,'#e7f0f2',700)+txt(x+270,512,c,10,'#93a7ae',500)
    return svg_shell('Grid-forward editorial', '02', defs,b)


def direction_glass():
    defs='''<linearGradient id="ambient" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#0b282d"/><stop offset=".48" stop-color="#18233a"/><stop offset="1" stop-color="#25152c"/></linearGradient><radialGradient id="orb"><stop stop-color="#32e5ce" stop-opacity=".6"/><stop offset="1" stop-color="#32e5ce" stop-opacity="0"/></radialGradient>'''
    defs += ''.join(artwork(0,0,1,1,i)[0] for i in range(4))
    b=rect(0,34,W,506,'url(#ambient)')+f'<circle cx="760" cy="195" r="275" fill="url(#orb)" opacity=".4"/>'
    b+=rect(26,58,906,462,'#09121a',26,'opacity=".48" stroke="#ffffff" stroke-opacity=".17" stroke-width="1"')
    b+=txt(55,92,'AK  /  TELEVISION',12,'#f4f8f8',800,'letter-spacing="1.4"')
    b+=txt(763,92,'LIVE  •  8:45 PM',10,'#32e5ce',700,'letter-spacing="1.1"')
    b+=txt(55,151,'A good night',32,'#f5f7fb',800)+txt(55,180,'starts with what you love.',16,'#bfccd1',500)
    # Floating live feature card
    b+=rect(55,206,536,173,'#ffffff',18,'opacity=".10" stroke="#ffffff" stroke-opacity=".28" stroke-width="1"')
    b+=rect(76,227,5,128,'#32e5ce',2)
    b+=txt(99,244,'●  LIVE NOW',10,'#32e5ce',700,'letter-spacing="1"')
    b+=txt(99,279,'Star Sports 1 Hindi HD',23,'#f5f8fa',800)
    b+=txt(99,304,'India vs Australia  ·  1st ODI',12,'#c0ccd1',500)
    b+=rect(99,322,124,38,'#eff8f7',20)+txt(116,346,'WATCH LIVE  ▶',10,'#08272b',800)
    b+=txt(645,224,'YOUR EVENING',10,'#cad8dc',700,'letter-spacing="1.5"')
    for i,(label,time) in enumerate([('Sports highlights','8:30 PM'),('Zee Cinema','9:00 PM'),('Late news','10:00 PM')]):
        y=251+i*49
        b+=rect(645,y,245,39,'#ffffff',12,'opacity=".09" stroke="#ffffff" stroke-opacity=".15"')
        b+=txt(660,y+17,label,11,'#edf4f5',600)+txt(660,y+32,time,9,'#9fb2ba',500)
    b+=txt(55,419,'PICK UP WHERE YOU LEFT OFF',10,'#d8e3e7',700,'letter-spacing="1.2"')
    for i in range(4):
        x=55+i*211
        _,art=artwork(x,436,190,65,i)
        b+=art
    b+=rect(0,34,W,506,'#0b1018',0,'opacity=".10"')
    return svg_shell('Glass / ambient', '03', defs,b)


def direction_movies():
    defs='''<linearGradient id="movieHero" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#19343b"/><stop offset="1" stop-color="#101626"/></linearGradient>'''
    defs += ''.join(artwork(0,0,1,1,i)[0] for i in range(4))
    b=rect(0,34,104,506,'#0d151f')+txt(22,77,'AK',28,'#32e5ce',900)+txt(24,96,'TV',10,'#d7e7e9',700)+nav(34,'Movies')
    b+=txt(130,77,'YOUR NEXT FAVORITE',10,'#32e5ce',700,'letter-spacing="2"')+txt(130,112,'Movies',29,'#f2f7f9',800)
    b+=rect(126,136,805,147,'url(#movieHero)',16)
    b+=txt(154,169,'FEATURED  ·  ADVENTURE',10,'#32e5ce',700,'letter-spacing="1.2"')
    b+=txt(154,207,'The Long Way Home',24,'#f2f7f9',800)
    b+=txt(154,232,'2025  ·  2h 08m  ·  ★ 8.1',11,'#c1cdd1',600)
    b+=txt(154,258,'DETAILS  →',10,'#32e5ce',800,'letter-spacing="1"')
    b+=txt(787,225,'AK',48,'#32e5ce',900,'opacity=".75"')
    b+=txt(130,321,'TRENDING TONIGHT',11,'#eef5f7',800,'letter-spacing="1.2"')
    for i,(title,year,rating) in enumerate([('Orbit','2025','8.4'),('North','2024','7.9'),('Ember','2023','8.1'),('Deep Blue','2025','7.7'),('First Light','2022','8.0')]):
        x=130+i*157
        b+=txt(x-3,377,str(i+1),53,'#39535b',900,'opacity=".5"')
        _,art=artwork(x+16,334,118,157,i)
        b+=art+rect(x+16,334,118,157,'none',9,'stroke="#48616a" stroke-width="1"')
        b+=txt(x+16,510,title,11,'#f1f7f8',700)+txt(x+16,527,f'{year}  ·  ★ {rating}',9,'#a8bac0',500)
    return svg_shell('Movies · poster shelf', '05', defs,b)


def direction_player():
    defs='''<linearGradient id="videoBg" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#142f38"/><stop offset=".45" stop-color="#223144"/><stop offset="1" stop-color="#090f17"/></linearGradient><linearGradient id="dark" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#03080e" stop-opacity=".7"/><stop offset=".5" stop-color="#03080e" stop-opacity=".05"/><stop offset="1" stop-color="#03080e" stop-opacity=".93"/></linearGradient>'''
    b=rect(0,34,W,506,'url(#videoBg)')
    b+=f'<circle cx="680" cy="260" r="170" fill="#32e5ce" opacity=".10"/><path d="M90 420 Q310 245 500 393 T960 305 V540 H0Z" fill="#08151c" opacity=".7"/>'
    b+=rect(0,34,W,506,'url(#dark)')
    b+=txt(42,84,'‹  LIVE TV',12,'#f4f8f8',700,'letter-spacing="1"')
    b+=txt(42,121,'Star Sports 1 Hindi HD',22,'#f4f8f8',800)
    b+=rect(815,62,98,29,'#c33958',14)+txt(839,81,'● LIVE',10,'#fff',800,'letter-spacing=".8"')
    b+=txt(42,401,'NOW  ·  India vs Australia  /  1st ODI',13,'#f2f7f8',700)
    b+=txt(42,425,'NEXT  ·  Post-match analysis',11,'#c4d1d5',500)
    b+=rect(42,441,876,4,'#52656b',2)+rect(42,441,492,4,'#32e5ce',2)
    b+=txt(42,466,'18:45',9,'#a9b9bf',600)+txt(864,466,'20:30',9,'#a9b9bf',600)
    b+=rect(42,482,145,35,'#f2f7f8',19)+txt(58,504,'Ⅱ  PLAY / PAUSE',9,'#0a2127',800)
    for i,label in enumerate(['SOURCE','PICTURE QUALITY','RETRY']):
        x=202+i*158
        b+=rect(x,482,144,35,'#101e28',18,'stroke="#73868d" stroke-width="1"')
        b+=txt(x+18,504,label,9,'#e5eff1',700)
    return svg_shell('Native player overlay', '06', defs,b)


def direction_type():
    defs='''<linearGradient id="slash" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#15373d"/><stop offset="1" stop-color="#101925"/></linearGradient>'''
    defs += ''.join(artwork(0,0,1,1,i)[0] for i in range(4))
    b=rect(0,34,W,506,'#080d14')+rect(0,34,10,506,'#32e5ce')
    b+=txt(45,83,'AK TV',13,'#32e5ce',900,'letter-spacing="2"')
    b+=txt(45,127,'LIVE',42,'#f2f6f9',900,'letter-spacing="-1"')
    b+=txt(45,167,'IS HAPPENING.',42,'#32e5ce',900,'letter-spacing="-1"')
    b+=txt(45,194,'Choose your channel. Stay for the moment.',12,'#9caeb5',500)
    b+=rect(45,219,870,2,'#27363d')
    b+=txt(45,264,'01',29,'#32e5ce',900)+txt(105,256,'STAR SPORTS',22,'#f2f6f8',900,'letter-spacing=".5"')
    b+=txt(105,279,'1 HINDI HD  /  LIVE',10,'#a9bbc1',700,'letter-spacing="1.2"')
    b+=rect(781,236,134,47,'#32e5ce',2)+txt(799,266,'TUNE IN  →',12,'#052229',900,'letter-spacing=".6"')
    b+=txt(45,327,'ON NOW',10,'#95a8af',800,'letter-spacing="1.4"')
    names=[('SPORTS','Star Sports'),('CARTOONS','Sony YAY'),('MOVIES','Zee Cinema'),('NEWS','News18')]
    for i,(cat,name) in enumerate(names):
        x=45+i*219
        shp,art=artwork(x,345,202,112,i)
        b+=art+rect(x,345,202,112,'none',0,'stroke="#49616a" stroke-width="1"')
        b+=txt(x,480,cat,9,'#32e5ce',800,'letter-spacing="1.1"')
        b+=txt(x,502,name,14,'#edf4f6',700)
    b+=txt(45,526,'← / →  BROWSE',9,'#82969e',600,'letter-spacing="1"')+txt(819,526,'8:45 PM  ·  SAMPLE',9,'#82969e',600,'letter-spacing="1"')
    return svg_shell('Bold typographic minimal', '04', defs,b)

DIRECTIONS=[
    ('01-rail-led-cinematic', direction_rail()),
    ('02-grid-forward-editorial', direction_editorial()),
    ('03-glass-ambient', direction_glass()),
    ('04-bold-typographic-minimal', direction_type()),
]
SUPPLEMENTS=[('movies-poster-shelf', direction_movies()), ('native-player-overlay', direction_player())]
for name, content in DIRECTIONS + SUPPLEMENTS:
    svg=OUT/f'{name}.svg'
    svg.write_text(content, encoding='utf-8')

# A simple browsable index shows four selectable home concepts plus Movies and player companions.
items='\n'.join(f'<figure><a href="{name}.png"><img src="{name}.png" alt="{title}"></a><figcaption>{title}</figcaption></figure>' for (name,_),title in zip(DIRECTIONS,['01 · Rail-led cinematic','02 · Grid-forward editorial','03 · Glass / ambient','04 · Bold typographic minimal']))
items += '\n<h2>Companion screens · previews, not extra directions</h2>'
items += '\n'.join(f'<figure><a href="{name}.png"><img src="{name}.png" alt="{title}"></a><figcaption>{title}</figcaption></figure>' for (name,_),title in zip(SUPPLEMENTS,['Movies · poster shelf','Native live-player overlay']))
(OUT/'index.html').write_text(f'''<!doctype html><meta charset="utf-8"><title>AK TV · UI directions</title><style>body{{margin:0;padding:28px;background:#080d14;color:#edf5f7;font:16px system-ui}}h1{{color:#32e5ce}}main{{display:grid;grid-template-columns:repeat(auto-fit,minmax(460px,1fr));gap:22px}}figure{{margin:0;padding:12px;background:#111b25;border:1px solid #263640;border-radius:14px}}img{{width:100%;height:auto;border-radius:8px}}figcaption{{padding:10px 2px 2px;font-weight:700}}p{{color:#a8b7be}}</style><h1>AK TV · Four TV-first UI directions</h1><p>Static concept previews · fictional/sample content · no playback UI code changed for these concepts.</p><main>{items}</main>''',encoding='utf-8')
print(f'Wrote {len(DIRECTIONS)} labelled concepts to {OUT}')

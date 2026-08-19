#!/usr/bin/env python3
"""Builds playwave-web.html: the single-file, no-server version of Playwave
(demo tracks inlined as data: URIs, styles.css inlined). This is the build
published as the claude.ai artifact / usable on any static host."""
import base64, json, pathlib

here = pathlib.Path(__file__).parent
root = here.parent

css = (root / 'css' / 'styles.css').read_text()
demos = []
meta = {
    'neon-dusk.ogg': ('Neon Dusk', 21),
    'glass-waves.ogg': ('Glass Waves', 23),
    'midnight-transit.ogg': ('Midnight Transit', 19),
    'sunset-loop.ogg': ('Sunset Loop', 25),
}
for name, (title, dur) in meta.items():
    b = (root / 'demo' / name).read_bytes()
    demos.append({'title': title, 'duration': dur,
                  'src': 'data:audio/ogg;base64,' + base64.b64encode(b).decode()})

tpl = (here / 'template.html').read_text()
out = tpl.replace('{{CSS}}', css).replace('{{DEMOS}}', json.dumps(demos))
(here / 'playwave-web.html').write_text(out)
print('wrote', here / 'playwave-web.html', f'({len(out)//1024} KB)')

from pathlib import Path
import pypdfium2 as pdf
import json
root=Path(__file__).resolve().parent
out=root/'pages';out.mkdir(exist_ok=True)
d=pdf.PdfDocument(root/'revised.pdf')
stats=[]
for i in range(len(d)):
    page=d[i];page.render(scale=1.5).to_pil().save(out/f'page-{i+1:02d}.png')
    tp=page.get_textpage();txt=tp.get_text_range()
    stats.append({'page':i+1,'chars':len(txt),'start':txt[:90],'end':txt[-100:]})
(root/'page_text.json').write_text(json.dumps(stats,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'pages':len(d),'short_pages':[s for s in stats if s['chars']<250]},ensure_ascii=False))

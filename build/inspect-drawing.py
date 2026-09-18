"""Prints the picture-related XML of one .docx, for when Word shows nothing.

    python build/inspect-drawing.py build/real-docx/009_....docx
"""
import re
import sys
import zipfile

path = sys.argv[1]
with zipfile.ZipFile(path) as zf:
    names = zf.namelist()
    media = [n for n in names if n.startswith('word/media/')]
    print('file      :', path)
    print('media     : %d part(s)' % len(media))
    for part in media[:4]:
        data = zf.read(part)
        print('   %-28s %9d bytes  magic=%r' % (part, len(data), data[:4]))

    document = zf.read('word/document.xml').decode('utf-8')
    print('\ndocument.xml root (first 700 chars):')
    print(document[:700])

    rels = zf.read('word/_rels/document.xml.rels').decode('utf-8')
    print('\nrelationships:')
    for line in re.findall(r'<Relationship[^>]*/>', rels):
        print('   ', line)

    print('\ncontent types:')
    for line in re.findall(r'<(?:Default|Override)[^>]*/>',
                           zf.read('[Content_Types].xml').decode('utf-8')):
        print('   ', line)

    drawings = re.findall(r'<w:r><w:drawing>.*?</w:drawing></w:r>', document, re.S)
    print('\ndrawings: %d' % len(drawings))
    if drawings:
        print('first drawing as written:')
        print(drawings[0][:1500])

import json
import plyvel
import sys
import time
import xml.sax
import xml.sax.handler


def a2d(attrs):
    return {n: attrs.getValue(n)
            for n in attrs.getNames()}


class MyHandler(xml.sax.handler.ContentHandler):

    def __init__(self):
        self.pyids = set()
        self.posts = {}
        self.id2obj = {}
        self.comments = False
#        self.outfile = open('json.json', 'w')

    def startElement(self, name, attrs):
        if name == 'row' and not self.comments:
            rowid = attrs.getValue('Id')
            ispy = False

            try:
                if '<javascript>' in attrs.getValue('Tags'):
                    ispy = True
            except KeyError:
                try:
                    ispy = attrs.getValue('ParentId') in self.pyids
                except KeyError:
                    if attrs.getValue('PostTypeId') not in ['3', '4', '5']:  # Tag Wiki
                        print(json.dumps(a2d(attrs)))

            if ispy:
                self.pyids.add(rowid)

            obj = a2d(attrs)
            obj['comments'] = []
            self.id2obj[obj['Id']] = obj

            if ispy:
                if obj['PostTypeId'] == '1':
                    self.posts[obj['Id']] = obj
                    self.posts[obj['Id']]['children'] = []
                else:
                    self.posts[obj['ParentId']]['children'].append(
                        obj
                    )

        elif (name == 'row' and self.comments and
                attrs.getValue('PostId') in self.id2obj):
            obj = a2d(attrs)
            self.id2obj[obj['PostId']]['comments'].append(obj)
        elif name != 'row':
            print(name)

handler = MyHandler()
parser = xml.sax.make_parser()
parser.setContentHandler(handler)
chunk = sys.stdin.read(10000)
chunk1 = None
done = 0
t0 = time.time()
while len(chunk) != 0:
    if '\x00' in chunk:
        chunk, chunk1 = chunk.split('\x00')
    parser.feed(chunk)
    done += 10000
    if done % (10000*1000) == 0:
        elapsed = time.time() - t0
        ALL = 9601970627 + 30613095889.
        print(done, elapsed*(1/(done/ALL) - 1))
        print(1/(done/ALL))
    if chunk1:
        chunk = chunk1 + sys.stdin.read(10240)
        parser = xml.sax.make_parser()
        parser.setContentHandler(handler)
        handler.comments = True
        chunk1 = None
    else:
        chunk = sys.stdin.read(10240)


"""db = plyvel.DB('testdb', create_if_missing=True)
db.put(b'count', bytes(str(len(handler.posts)), 'utf-8'))
for k in handler.posts:
    db.put(bytes(k, 'utf-8'), bytes(json.dumps(handler.posts[k]), 'utf-8'))

db.close()"""

#xml.sax.parse(sys.stdin, handler)

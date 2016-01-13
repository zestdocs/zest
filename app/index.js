var nodelucene = require("./build/Release/nodelucene")
var idx = new nodelucene.LuceneIndex('SOPython');
idx.startWriting();
var levelup = require('levelup')
var data = levelup('/Users/jkozera/Downloads/stackexchange/python')


var p5 = require('parse5')
function uf(nm) {
    var body='', headers = '', d = new p5.SAXParser(), inHeader = false;
    d.on('text', function(text) {
        if (inHeader) {
            headers += ' ' + text;
        } else {
            body += ' ' + text;
        }
    });
    /* d.on('startTag', function(name) {
        console.log(name);
        if (name == 'dt') inHeader = true;
    })
    d.on('endTag', function(name) {
        if (name == 'dt') inHeader = false;
    }) */
    d.write(nm);
    return body; /* {
        body: body,
        headers: headers
    } */
}

function quf(data, leaf) {
    var ret = data.Title + ' ' + uf(data.Body);
    for (var j = 0; j < data.comments.length; ++j) {
        ret += ' ' + data.comments[j].Text;
    }
    for (var i = 0; i < data.answers.length; ++i) {
        var answer = data.answers[i];
        ret += ' ' + uf(answer.Body);
        for (var j = 0; j < answer.comments.length; ++j) {
            ret += ' ' + answer.comments[j].Text;
        }
    }

    return ret;
}

var i = 0;
data.createReadStream().on('data', function(v) {
    var data = JSON.parse(v.value);
    idx.addFile(
        data.Id + ';' + data.Title,
        quf(data)
    )
    i += 1;
    if (i % 1000 === 0) console.log(i);
}).on('end', function() {
    idx.endWriting()
    console.log('end');
});
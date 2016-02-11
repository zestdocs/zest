window.ipcRenderer = require('electron').ipcRenderer;
ipcRenderer.on('reset', function(event, className) {
    document.getElementsByTagName('body')[0].className = className;
    document.getElementsByTagName('body')[0].innerHTML = '';
});
ipcRenderer.on('add', function(event, tag, html, attrs) {
    var el;
    if (tag === 3) {
        el = document.createTextNode(html);
    } else {
        el = document.createElement(tag);
        el.innerHTML = html;
        for (var i = 0; i < attrs.length; ++i) {
            el.setAttribute(attrs[i].name, attrs[i].value);
        }
    }
    document.getElementsByTagName('body')[0].appendChild(el);
});
ipcRenderer.on('hash', function(event, hash) {
    location.hash = hash;
});
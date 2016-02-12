window.ipcRenderer = require('electron').ipcRenderer;
window.osPlatform = process.platform;
ipcRenderer.on('reset', function(event, className) {
    document.body.className = className;
    document.body.innerHTML = '';
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
    document.body.appendChild(el);
});
ipcRenderer.on('hash', function(event, hash) {
    location.hash = hash;
});
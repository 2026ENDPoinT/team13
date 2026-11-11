// Viewer request on the default (static site) behavior only. /api, /ws, /vod and /doc never reach it.
// spa: extensionless paths serve /index.html (client-side routing).
// directory: extensionless paths serve <path>/index.html (static export per route).
var MODE = '${mode}';

function handler(event) {
  var request = event.request;
  var uri = request.uri;

  if (uri.endsWith('/')) {
    request.uri = MODE === 'spa' ? '/index.html' : uri + 'index.html';
    return request;
  }

  var lastSegment = uri.substring(uri.lastIndexOf('/') + 1);
  if (lastSegment.indexOf('.') === -1) {
    request.uri = MODE === 'spa' ? '/index.html' : uri + '/index.html';
  }
  return request;
}

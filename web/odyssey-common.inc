gzip on;
gzip_static on;
gzip_comp_level 6;
gzip_min_length 1024;
gzip_proxied any;
gzip_vary on;
gzip_types application/javascript text/css application/json image/svg+xml font/woff2;

location /v1/ {
    proxy_pass http://odyssey_api;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
    proxy_read_timeout 60s;
}

# Content-hashed by the build: never changes, so browsers and Cloudflare keep it for a year.
location /assets/ {
    add_header Cache-Control "public, max-age=31536000, immutable";
    try_files $uri =404;
}

location / {
    add_header Cache-Control "no-cache";
    try_files $uri /index.html;
}

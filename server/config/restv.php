<?php

return [
    // Shared secret the app sends as X-Restv-Key. Generate one: php -r "echo bin2hex(random_bytes(16));"
    'key' => env('RESTV_KEY', ''),

    // Which day counts as "today", and the timezone of times shown on LiveSoccerTV when no timestamp is found.
    'timezone' => env('RESTV_TIMEZONE', 'Asia/Baghdad'),
    'source_timezone' => env('RESTV_SOURCE_TIMEZONE', env('RESTV_TIMEZONE', 'Asia/Baghdad')),

    // LiveSoccerTV shows the channels of the country it thinks the visitor is in (your server's location).
    // To get Iraq/MENA listings, pick your country on livesoccertv.com in a browser, then copy that site's
    // cookies (browser dev tools → Application → Cookies) into RESTV_LSTV_COOKIES as "name=value; name2=value2".
    'lstv_cookies' => env('RESTV_LSTV_COOKIES', ''),

    'user_agent' => env(
        'RESTV_USER_AGENT',
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36'
    ),
];

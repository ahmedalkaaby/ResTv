# restv-server

Daily football matches and their TV channels for the Restv TV app.
Twice a day it reads today's and tomorrow's schedule from LiveSoccerTV and saves it as JSON;
Restv asks for it with one request:

```
GET https://restv.k3be.dev/api/matches?date=2026-10-19
Header: X-Restv-Key: <RESTV_KEY from .env>
```

No database is used. Data lives in `storage/app/private/matches/*.json` (one file per day, a week is kept).

## What's in here

| File | Job |
| --- | --- |
| `app/Services/LiveSoccerTv.php` | Downloads and parses one day's schedule |
| `app/Console/Commands/FetchMatches.php` | `php artisan restv:matches` saves today + tomorrow |
| `app/Http/Controllers/MatchesController.php` | `/api/matches`, checks the key, serves the saved JSON |
| `config/restv.php` | Settings read from `.env` |
| `routes/web.php` | The API route |
| `routes/console.php` | Runs the fetch at 05:00 and 15:00 Baghdad time |
| `.env` | Already filled in: app key, `RESTV_KEY`, timezone, file cache/sessions |

`.env` holds secrets. Don't commit it or share it.

## 1. Test it on your Mac

Needs PHP 8.3+ and Composer (`brew install php composer` if missing).

```
cd ~/Documents/restv-server
composer install
php artisan restv:matches --dump
php artisan serve
```

`restv:matches --dump` should print something like `2026-10-19: 84 matches, 61 with channels`
plus three sample matches. Then open:

```
http://127.0.0.1:8000/api/matches?key=YOUR_RESTV_KEY
```

(`YOUR_RESTV_KEY` is the `RESTV_KEY=` line in `.env`.)

## 2. Put it on the shared server (cPanel)

1. **Check PHP:** cPanel → *MultiPHP Manager* (or *Select PHP Version*): set PHP **8.3 or newer** for the
   domain you'll use. This project uses Laravel 13, which needs 8.3.
2. **Install dependencies on your Mac first:** run `composer install --no-dev --optimize-autoloader` so the
   `vendor/` folder is complete (shared hosts often have no Composer).
3. **Zip and upload:** zip the whole `restv-server` folder, including `vendor/` and `.env`
   (Finder hides `.env`; press ⌘⇧. to show hidden files). In cPanel *File Manager*, upload it to your
   home folder (next to `public_html`, **not inside it**) and *Extract*. You should end up with
   `/home/USERNAME/restv-server`.
4. **Subdomain:** cPanel → *Domains* → *Create A New Domain*: `restv.k3be.dev`, untick "share document
   root", set the document root to `restv-server/public`.
5. **SSL:** cPanel → *SSL/TLS Status* → run AutoSSL for `restv.k3be.dev` (DNS can take a few minutes).
6. **Permissions:** in File Manager, make sure `storage/` and `bootstrap/cache/` are writable (755 is usually
   fine on cPanel).
7. **Cron job:** cPanel → *Cron Jobs* → *Add New Cron Job*, every minute:
   ```
   * * * * * cd /home/USERNAME/restv-server && php artisan schedule:run >> /dev/null 2>&1
   ```
   If `php` there is the wrong version, use the full path your host gives, e.g.
   `/opt/cpanel/ea-php83/root/usr/bin/php`. If your host limits crons, use this instead (05:00 and 15:00):
   ```
   0 5,15 * * * cd /home/USERNAME/restv-server && php artisan restv:matches --days=2 >> /dev/null 2>&1
   ```
8. **First fetch:** cPanel → *Terminal* (or SSH):
   ```
   cd ~/restv-server
   php artisan config:cache
   php artisan restv:matches --dump
   ```
9. **Check in a browser:** `https://restv.k3be.dev/api/matches?key=YOUR_RESTV_KEY`
10. **In Restv on the TV:** Settings → *Matches server* → URL `https://restv.k3be.dev/api/matches`,
    Key = your `RESTV_KEY` → *Save server*. Then open the *Matches* tab.

After changing `.env` on the server, run `php artisan config:cache` again.

## Troubleshooting

- **0 matches:** LiveSoccerTV changed its page or blocked the server. `--dump` saves the raw page to
  `storage/app/private/matches/raw-<date>.html`. If it's a "checking your browser" page, the server's IP is
  blocked: run the fetch on your Mac and upload the `.json` files to the same folder on the server.
  Otherwise send the file's first part to Claude to adjust the parser.
- **Wrong country's channels** (US/UK instead of beIN MENA): LiveSoccerTV picks the country from the
  server's IP. Choose your country on livesoccertv.com in your browser, copy that site's cookies
  (browser dev tools → Application → Cookies) into `RESTV_LSTV_COOKIES="name=value; name2=value2"`,
  then `php artisan config:cache` and fetch again.
- **Times off by hours:** set `RESTV_SOURCE_TIMEZONE` to the timezone the site shows times in.
- **401 in the app:** the key in Restv doesn't match `RESTV_KEY`.
- **500 error:** check `storage/logs/laravel.log`.

This fetches two pages twice a day for personal use. Please don't raise the frequency.

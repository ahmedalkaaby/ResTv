# Restv matches server

A tiny Laravel module that, once or twice a day, reads today's and tomorrow's football from
LiveSoccerTV (kick-off, competition, teams, TV channels per match) and serves it as JSON to Restv.
The TV never scrapes anything; if LiveSoccerTV changes its page, you fix one file here.

```
GET https://restv.k3be.dev/api/matches?date=2026-10-19
Header: X-Restv-Key: <your key>
```

## Files

| Copy this | To your Laravel app |
| --- | --- |
| `app/Services/LiveSoccerTv.php` | `app/Services/LiveSoccerTv.php` |
| `app/Console/Commands/FetchMatches.php` | `app/Console/Commands/FetchMatches.php` |
| `app/Http/Controllers/MatchesController.php` | `app/Http/Controllers/MatchesController.php` |
| `config/restv.php` | `config/restv.php` |

## Setup on shared hosting (cPanel)

1. **Make the Laravel app** on your computer (needs PHP 8.2+ and Composer):
   ```
   composer create-project laravel/laravel restv-server
   ```
   Copy the four files above into it.

2. **Add the route** at the end of `routes/web.php`:
   ```php
   Route::get('/api/matches', [\App\Http\Controllers\MatchesController::class, 'index']);
   ```

3. **Add the schedule** at the end of `routes/console.php` (Laravel 11/12):
   ```php
   use Illuminate\Support\Facades\Schedule;

   Schedule::command('restv:matches --days=2')->twiceDaily(5, 15)->timezone('Asia/Baghdad');
   ```
   Twice a day catches late channel announcements. Change to `->dailyAt('05:00')` for once a day.

4. **Settings** in `.env`:
   ```
   APP_ENV=production
   APP_DEBUG=false
   RESTV_KEY=paste-a-long-random-string-here
   RESTV_TIMEZONE=Asia/Baghdad
   RESTV_LSTV_COOKIES=
   ```
   Make a key with `php -r "echo bin2hex(random_bytes(16));"` and keep it; the app needs the same value.

5. **Upload** the whole folder to your hosting, outside `public_html`, e.g. `/home/USER/restv-server`.

6. **Subdomain:** in cPanel → Domains, create `restv.k3be.dev` with its document root set to
   `/home/USER/restv-server/public`. Turn on SSL (AutoSSL / Let's Encrypt) for it.

7. **Cron job:** cPanel → Cron Jobs, every minute:
   ```
   * * * * * cd /home/USER/restv-server && php artisan schedule:run >> /dev/null 2>&1
   ```
   If your host only allows a few cron runs, use this instead (05:00 and 15:00 server time):
   ```
   0 5,15 * * * cd /home/USER/restv-server && php artisan restv:matches --days=2 >> /dev/null 2>&1
   ```
   On some hosts the PHP binary has a version in its path, like `/usr/local/bin/ea-php83`.

8. **First run and test** (cPanel Terminal or SSH):
   ```
   cd ~/restv-server
   php artisan config:cache
   php artisan restv:matches --dump
   ```
   You should see lines like `2026-10-19: 84 matches, 61 with channels`, then three sample matches.
   Then open in a browser:
   `https://restv.k3be.dev/api/matches?key=YOUR_KEY`

9. **In Restv:** Settings → Matches server → enter `https://restv.k3be.dev/api/matches` and the key.

## If something looks wrong

- **0 matches:** LiveSoccerTV changed its page or blocked the server. `--dump` saved the page to
  `storage/app/matches/raw-<date>.html`; open it. If it's a "checking your browser" page, the host's IP is
  being blocked; try again later or run the command from another machine and upload the JSON.
- **Wrong country's channels** (e.g. US channels instead of beIN MENA): LiveSoccerTV picks the country
  from the server's IP. Choose your country on livesoccertv.com in your browser, copy that site's cookies
  into `RESTV_LSTV_COOKIES`, run `php artisan config:cache`, and fetch again.
- **Times off by hours:** set `RESTV_SOURCE_TIMEZONE` to the timezone the site shows times in.

Be considerate: this fetches a couple of pages a day for personal use. Don't raise the frequency.

<?php

namespace App\Console\Commands;

use App\Services\LiveSoccerTv;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\Storage;
use Throwable;

/**
 * php artisan restv:matches            today + tomorrow
 * php artisan restv:matches --days=3   today + next 2 days
 * php artisan restv:matches --dump     also save the raw HTML for debugging
 */
class FetchMatches extends Command
{
    protected $signature = 'restv:matches {--days=2 : How many days to fetch, starting today} {--dump : Save the raw HTML too}';

    protected $description = 'Fetch football matches and their TV channels for Restv';

    public function handle(LiveSoccerTv $lstv): int
    {
        $tz = config('restv.timezone');
        $days = max(1, (int) $this->option('days'));
        $failed = false;

        for ($i = 0; $i < $days; $i++) {
            $date = now($tz)->addDays($i)->toDateString();
            try {
                $html = $lstv->download($date);
                if ($this->option('dump')) {
                    Storage::put("matches/raw-{$date}.html", $html);
                    $this->line("Saved raw HTML to storage/app/matches/raw-{$date}.html");
                }
                $matches = $lstv->parse($html, $date);

                Storage::put("matches/{$date}.json", json_encode([
                    'date' => $date,
                    'timezone' => $tz,
                    'generated_at' => now()->toIso8601String(),
                    'source' => 'livesoccertv',
                    'matches' => $matches,
                ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));

                $withChannels = count(array_filter($matches, fn ($m) => ! empty($m['channels'])));
                $this->info("{$date}: ".count($matches)." matches, {$withChannels} with channels");

                if ($this->option('dump')) {
                    foreach (array_slice($matches, 0, 3) as $m) {
                        $this->line("  {$m['kickoff']}  {$m['home']} v {$m['away']}  ->  ".implode(', ', $m['channels']));
                    }
                }
            } catch (Throwable $e) {
                $failed = true;
                $this->error("{$date}: {$e->getMessage()}");
            }
        }

        // Keep a week of history, delete older files.
        foreach (Storage::files('matches') as $file) {
            if (preg_match('/(\d{4}-\d{2}-\d{2})/', $file, $m) && $m[1] < now($tz)->subDays(7)->toDateString()) {
                Storage::delete($file);
            }
        }

        return $failed ? self::FAILURE : self::SUCCESS;
    }
}

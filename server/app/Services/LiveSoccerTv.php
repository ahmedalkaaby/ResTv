<?php

namespace App\Services;

use DOMDocument;
use DOMElement;
use DOMXPath;
use Illuminate\Support\Facades\Http;
use RuntimeException;

/**
 * Reads one day of football from LiveSoccerTV's schedule page:
 * kick-off time, competition, teams, and the TV channels listed for each match.
 *
 * LiveSoccerTV has no public API, so this parses their HTML. It looks for links
 * rather than exact CSS classes (/competitions/, /match/, /channels/), which survives
 * most redesigns. If a day ever comes back empty, run `php artisan restv:matches --dump`
 * and check the saved HTML.
 */
class LiveSoccerTv
{
    public function fetchDay(string $date): array
    {
        $html = $this->download($date);

        return $this->parse($html, $date);
    }

    public function download(string $date): string
    {
        $url = "https://www.livesoccertv.com/schedules/{$date}/";

        $headers = [
            'User-Agent' => config('restv.user_agent'),
            'Accept' => 'text/html,application/xhtml+xml',
            'Accept-Language' => 'en-GB,en;q=0.9',
        ];
        if ($cookies = config('restv.lstv_cookies')) {
            $headers['Cookie'] = $cookies;
        }

        $response = Http::withHeaders($headers)->timeout(40)->retry(2, 3000)->get($url);

        if (! $response->successful()) {
            throw new RuntimeException("LiveSoccerTV answered HTTP {$response->status()} for {$date}");
        }

        return $response->body();
    }

    public function parse(string $html, string $date): array
    {
        libxml_use_internal_errors(true);
        $dom = new DOMDocument();
        $dom->loadHTML('<?xml encoding="utf-8"?>'.$html);
        $xp = new DOMXPath($dom);

        $matches = [];
        $competition = null;

        /** @var DOMElement $tr */
        foreach ($xp->query('//tr') as $tr) {
            $matchLink = $xp->query('.//a[contains(@href, "/match/")]', $tr)->item(0);
            $compLink = $xp->query('.//a[contains(@href, "/competitions/")]', $tr)->item(0);

            // A competition header row: remember it for the matches below.
            if ($compLink && ! $matchLink) {
                $competition = $this->clean($compLink->textContent);
                continue;
            }
            if (! $matchLink) {
                continue;
            }

            [$home, $away] = $this->teams($this->clean($matchLink->textContent)) ?? [null, null];
            if (! $home || ! $away) {
                continue;
            }

            $channels = [];
            foreach ($xp->query('.//a[contains(@href, "/channels/")]', $tr) as $a) {
                /** @var DOMElement $a */
                $name = $this->channelName($a->getAttribute('title') ?: $a->textContent);
                if ($name !== '' && ! in_array($name, $channels, true)) {
                    $channels[] = $name;
                }
            }

            $matches[] = [
                'id' => substr(md5("{$date}|{$home}|{$away}"), 0, 12),
                'kickoff' => $this->kickoff($xp, $tr, $date),
                'competition' => $competition,
                'home' => $home,
                'away' => $away,
                'channels' => $channels,
                'url' => 'https://www.livesoccertv.com'.$matchLink->getAttribute('href'),
            ];
        }

        // Earliest first; matches without a parsed time go last.
        usort($matches, fn ($a, $b) => strcmp($a['kickoff'] ?: 'z', $b['kickoff'] ?: 'z'));

        return $matches;
    }

    private function teams(string $text): ?array
    {
        $parts = preg_split('/\s+(?:vs\.?|v)\s+/i', $text);

        return count($parts) === 2 ? [trim($parts[0]), trim($parts[1])] : null;
    }

    /**
     * Kick-off as ISO 8601. Prefers a unix timestamp in a data attribute (exact),
     * else reads "HH:MM" from the row in the configured timezone.
     */
    private function kickoff(DOMXPath $xp, DOMElement $tr, string $date): ?string
    {
        foreach ($xp->query('.//*[@dv or @data-time or @data-timestamp]', $tr) as $el) {
            /** @var DOMElement $el */
            $raw = $el->getAttribute('dv') ?: $el->getAttribute('data-time') ?: $el->getAttribute('data-timestamp');
            if (ctype_digit($raw)) {
                $ts = strlen($raw) > 11 ? intdiv((int) $raw, 1000) : (int) $raw;

                return gmdate('c', $ts);
            }
        }

        if (preg_match('/(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)/', $tr->textContent, $m)) {
            $tz = new \DateTimeZone(config('restv.source_timezone'));
            $local = new \DateTimeImmutable("{$date} {$m[1]}:{$m[2]}", $tz);

            return $local->setTimezone(new \DateTimeZone('UTC'))->format('c');
        }

        return null;
    }

    /** "beIN Sports English 2 (live stream available)" → "beIN Sports English 2"; drops country lists too. */
    private function channelName(string $s): string
    {
        $s = preg_replace('/\s*\([^)]*\)?\s*$/u', '', $this->clean($s)); // trailing "(...)" even if cut off
        $s = preg_replace('/\s*\([^)]*\)/u', '', $s);

        return trim($s);
    }

    private function clean(string $s): string
    {
        return trim(preg_replace('/\s+/u', ' ', $s));
    }
}

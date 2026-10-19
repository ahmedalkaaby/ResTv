<?php

namespace App\Http\Controllers;

use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Storage;

class MatchesController extends Controller
{
    /** GET /api/matches?date=YYYY-MM-DD — serves the file the fetch wrote. Never scrapes on request. */
    public function index(Request $request): JsonResponse
    {
        $this->checkKey($request);

        $date = (string) $request->query('date', now(config('restv.timezone'))->toDateString());
        abort_unless(preg_match('/^\d{4}-\d{2}-\d{2}$/', $date) === 1, 422, 'date must be YYYY-MM-DD');

        $path = "matches/{$date}.json";
        if (! Storage::exists($path)) {
            return response()->json(['date' => $date, 'status' => 'not_fetched', 'matches' => []]);
        }

        return response()->json(json_decode(Storage::get($path), true))
            ->header('Cache-Control', 'public, max-age=600');
    }

    /**
     * GET /api/matches/refresh — runs the fetch now. For an external scheduler (cron-job.org)
     * when the host's own cron is awkward. Refuses to run more than once every 30 minutes.
     */
    public function refresh(Request $request): JsonResponse
    {
        $this->checkKey($request);

        $last = Storage::exists('matches/.last-refresh') ? (int) Storage::get('matches/.last-refresh') : 0;
        if (time() - $last < 1800) {
            return response()->json(['status' => 'skipped', 'reason' => 'fetched less than 30 minutes ago']);
        }
        Storage::put('matches/.last-refresh', (string) time());

        @set_time_limit(120);
        $code = Artisan::call('restv:matches', ['--days' => 2]);

        return response()->json([
            'status' => $code === 0 ? 'ok' : 'failed',
            'output' => trim(Artisan::output()),
        ], $code === 0 ? 200 : 500);
    }

    private function checkKey(Request $request): void
    {
        $key = (string) config('restv.key');
        $given = (string) ($request->header('X-Restv-Key') ?? $request->query('key', ''));
        abort_if($key === '' || ! hash_equals($key, $given), 401, 'Wrong or missing key');
    }
}

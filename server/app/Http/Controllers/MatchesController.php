<?php

namespace App\Http\Controllers;

use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Storage;

/**
 * GET /api/matches?date=YYYY-MM-DD   (header X-Restv-Key: <RESTV_KEY>)
 * Serves the file the daily command wrote. Never scrapes on request, so it's fast and polite.
 */
class MatchesController extends Controller
{
    public function index(Request $request): JsonResponse
    {
        $key = (string) config('restv.key');
        $given = (string) ($request->header('X-Restv-Key') ?? $request->query('key', ''));
        abort_if($key === '' || ! hash_equals($key, $given), 401, 'Wrong or missing key');

        $date = (string) $request->query('date', now(config('restv.timezone'))->toDateString());
        abort_unless(preg_match('/^\d{4}-\d{2}-\d{2}$/', $date) === 1, 422, 'date must be YYYY-MM-DD');

        $path = "matches/{$date}.json";
        if (! Storage::exists($path)) {
            return response()->json(['date' => $date, 'status' => 'not_fetched', 'matches' => []]);
        }

        return response()->json(json_decode(Storage::get($path), true))
            ->header('Cache-Control', 'public, max-age=600');
    }
}

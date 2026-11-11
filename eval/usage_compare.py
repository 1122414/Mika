"""Compare Mika usage exports without uploading request contents. Python 3.10+."""
import argparse
import json
from decimal import Decimal
from pathlib import Path


def read_run(path):
    total = outcome = None
    replies = missing = lookups = 0
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            if not line.strip():
                continue
            event = json.loads(line)
            kind = event["type"]
            if kind == "model_calls":
                if total is not None:
                    raise ValueError("multiple run totals in one file")
                total = event
            elif kind == "model_reply":
                replies += 1
                lookups += event.get("tool") in {"history_read", "view_past_screens"}
            elif kind == "task_ended":
                outcome = event["outcome"]
            elif kind == "unreadable_event":
                missing += 1
    return outcome, replies, total, missing, lookups


def summarize(paths, verified, prices=None, tariffs=None):
    logical = http = uncached = cached = output = replies = passed = reported_success = 0
    missing = unknown_http = no_totals = unfinished = 0
    lookups = 0
    grouped_cost = Decimal(0)
    priced_all_groups = True
    by_source = {}
    for path in paths:
        outcome, steps, total, gaps, queries = read_run(path)
        lookups += queries
        passed += path.resolve() in verified
        reported_success += outcome == "succeeded"
        unfinished += outcome is None
        replies += steps
        missing += gaps
        if total is None:
            no_totals += 1
            continue
        required = ["calls", "inputTokens", "outputTokens", "cachedTokens", "unreported"]
        if any(type(total.get(k)) is not int or total[k] < 0 for k in required):
            raise ValueError("incomplete or invalid run totals")
        if total["cachedTokens"] > total["inputTokens"]:
            raise ValueError("cached exceeds total input")
        logical += total["calls"]
        unknown_http += total.get("httpCalls") is None or total.get("httpUnreported") is None
        http += total.get("httpCalls") or 0
        missing += max(total["unreported"], total.get("httpUnreported") or 0)
        cached += total["cachedTokens"]
        uncached += total["inputTokens"] - total["cachedTokens"]
        output += total["outputTokens"]
        row_counts = [0, 0, 0]
        for g in total.get("groups", []):
            key = (g["provider"], g["model"], g["purpose"])
            values = (g.get("inputTokens", 0) - g.get("cachedTokens", 0), g.get("cachedTokens", 0), g.get("outputTokens", 0))
            if any(type(n) is not int or n < 0 for n in values):
                raise ValueError("invalid grouped token counts")
            acc = by_source.setdefault(key, [0, 0, 0])
            for index, n in enumerate(values):
                row_counts[index] += n
                acc[index] += n
            rate = (tariffs or {}).get(key[0], {}).get(key[1])
            if rate is None:
                priced_all_groups = False
            else:
                grouped_cost += sum(n * p for n, p in zip(values, rate)) / 1_000_000
        if row_counts != [total["inputTokens"] - total["cachedTokens"], total["cachedTokens"], total["outputTokens"]]:
            priced_all_groups = False
    reported = uncached + cached + output
    complete = not (missing or no_totals or unknown_http or unfinished)
    cost = None
    if prices is not None and complete:
        cost = sum(n * price for n, price in zip((uncached, cached, output), prices)) / 1_000_000
    elif tariffs is not None and complete and priced_all_groups:
        cost = grouped_cost
    return dict(tasks=len(paths), reported_success=reported_success, verified_success=passed,
                unfinished=unfinished, main_replies=replies, proposed_history_lookups=lookups,
                known_input=uncached + cached, known_cache_share=round(cached / (uncached + cached), 6) if uncached + cached else None, known_calls=logical, known_http=http,
                known_uncached=uncached, known_cached=cached, known_output=output,
                total_tokens_per_task=round(reported / len(paths), 1) if complete else None,
                total_tokens_per_verified_success=round(reported / passed, 1) if passed and complete else None,
                estimated_cost=str(cost) if cost is not None else None,
                cost_per_verified_success=str(cost / passed) if cost is not None and passed else None,
                usage_complete=complete, groups=[dict(provider=k[0], model=k[1], purpose=k[2],
                    uncached=v[0], cached=v[1], output=v[2]) for k, v in sorted(by_source.items())])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, nargs="+", required=True)
    parser.add_argument("--after", type=Path, nargs="+", default=[])
    parser.add_argument("--verified", type=Path, nargs="+", default=[],
                        help="exports independently checked against the task acceptance criteria")
    pricing = parser.add_mutually_exclusive_group()
    pricing.add_argument("--uniform-prices", type=Decimal, nargs=3, metavar=("MISS", "HIT", "OUTPUT"),
                        help="per million tokens, only when all calls share this tariff; otherwise omit")
    pricing.add_argument("--tariffs", type=Path, help='JSON: {provider: {model: [miss, hit, output]}} per million')
    args = parser.parse_args()
    paths = [p.resolve() for p in args.before + args.after]
    verified = {p.resolve() for p in args.verified}
    if len(set(paths)) != len(paths) or verified - set(paths):
        parser.error("supply each export once; verified files must belong to a group")
    if args.uniform_prices and any(not p.is_finite() or p < 0 for p in args.uniform_prices):
        parser.error("prices must be finite and non-negative")
    try:
        tariffs = None
        if args.tariffs:
            tariffs = json.loads(args.tariffs.read_text(encoding="utf-8"), parse_float=Decimal, parse_int=Decimal)
            if not isinstance(tariffs, dict) or any(not isinstance(models, dict) or any(
                not isinstance(rate, list) or len(rate) != 3 or any(
                    not isinstance(p, Decimal) or not p.is_finite() or p < 0 for p in rate)
                for rate in models.values()) for models in tariffs.values()):
                parser.error("invalid tariff table")
        print(json.dumps({name: summarize(group, verified, args.uniform_prices, tariffs)
                          for name, group in (("before", args.before), ("after", args.after)) if group}, indent=2))
    except (OSError, ValueError, TypeError, KeyError) as e:
        parser.error(str(e))


if __name__ == "__main__":
    main()

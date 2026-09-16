"""Provider-neutral sleep contract. Times retain the recorded offset, not PC timezone."""
from dataclasses import dataclass, field, asdict
from datetime import datetime, date, timedelta, timezone
from enum import Enum
import math


class Category(str, Enum):
    AUTH_REQUIRED = 'AUTH_REQUIRED'
    NETWORK = 'NETWORK'
    RATE_LIMIT = 'RATE_LIMIT'
    SERVER = 'SERVER'
    CONFIGURATION = 'CONFIGURATION'
    DECODING = 'DECODING'


class HealthError(Exception):
    def __init__(self, category, retry_at=0):
        self.category, self.retry_at = Category(category), retry_at
        super().__init__(self.category.value)


@dataclass
class Complete:
    logs: list


@dataclass
class Pending:
    logs: list
    retry_after: float = 3


@dataclass
class Failed:
    category: Category
    retry_at: float = 0


@dataclass
class SleepStatus:
    last_attempt: float = 0
    last_verified: float = 0
    last_changed: float = 0
    phase: str = 'UNKNOWN'
    error: str | None = None
    next_retry: float = 0
    server_retry: float = 0
    retry_count: int = 0

    def opacity(self, now):
        if not self.last_verified or self.error == 'AUTH_REQUIRED' or self.phase == 'DISCONNECTED':
            return .3
        age = max(0, now - self.last_verified)
        return .3 if age >= 14400 else .6 if age >= 3600 else 1.0


def reconcile(old, incoming, start, end, complete):
    records = [s for s in old if not (complete and start <= s['dateOfSleep'] <= end)]
    for item in incoming:
        if not start <= item['dateOfSleep'] <= end:
            continue
        records = [s for s in records if not (
            s.get('provider', 'fitbit') == item.get('provider', 'fitbit') and (
                item.get('sourceRecordId') and s.get('sourceRecordId') == item['sourceRecordId'] or
                (s['startTime'], s['endTime']) == (item['startTime'], item['endTime'])))]
        records.append(item)
    cutoff = (date.fromisoformat(end) - timedelta(days=14)).isoformat()
    return sorted((s for s in records if s['dateOfSleep'] >= cutoff),
                  key=lambda s: (s.get('endInstant', s['endTime']), s['startTime'], s.get('sourceRecordId', '')), reverse=True)


def instant(value):
    dt = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if dt.tzinfo is None:
        raise ValueError('missing timezone')
    return dt


def local_time(value, offset):
    if not isinstance(offset, str) or not offset.endswith('s'):
        raise ValueError('missing offset')
    seconds = float(offset[:-1])
    if not math.isfinite(seconds) or abs(seconds) >= 86400:
        raise ValueError('invalid offset')
    return instant(value).astimezone(timezone(timedelta(seconds=seconds)))


def sleep_records(points):
    """Decode only a fully collected page set; a malformed record fails the whole range."""
    logs, main_flags, staged = [], {}, {}
    pending = False
    for point in points:
        sleep = point['sleep']
        interval = sleep['interval']
        start = local_time(interval['startTime'], interval['startUtcOffset'])
        end = local_time(interval['endTime'], interval['endUtcOffset'])
        duration = (end.astimezone(timezone.utc) - start.astimezone(timezone.utc)).total_seconds()
        if not 0 < duration <= 7 * 86400:
            raise ValueError('invalid interval')
        metadata = sleep.get('metadata', {})
        processed = metadata.get('processed', False)
        if not isinstance(processed, bool):
            raise ValueError('invalid processing state')
        pending |= not processed
        summary = sleep.get('summary') or {}
        if 'minutesAsleep' not in summary:
            if not processed:
                continue  # No usable duration yet; preserve previously saved records.
            raise ValueError('missing sleep summary')
        minutes = int(summary['minutesAsleep'])
        if minutes < 0 or minutes > math.ceil(duration / 60):
            raise ValueError('invalid asleep duration')
        name = point.get('dataPointName') or point.get('name')
        if not isinstance(name, str) or '/dataTypes/sleep/dataPoints/' not in name:
            raise ValueError('missing record identity')
        flag = metadata.get('mainSleep', metadata.get('main'))
        if flag is not None and not isinstance(flag, bool):
            raise ValueError('invalid main classification')
        main_flags[name] = flag
        staged[name] = sleep.get('type') == 'STAGES'
        logs.append(dict(
            provider='google_health', sourceRecordId=name, logId=name,
            dateOfSleep=end.date().isoformat(), minutesAsleep=minutes,
            isMainSleep=flag is True, isNap=metadata.get('nap', False), processed=processed,
            timeInBed=int(duration / 60), duration=round(duration * 1000),
            startTime=start.replace(tzinfo=None).isoformat(timespec='milliseconds'),
            endTime=end.replace(tzinfo=None).isoformat(timespec='milliseconds'),
            startInstant=interval['startTime'], endInstant=interval['endTime'],
            startUtcOffset=interval['startUtcOffset'], endUtcOffset=interval['endUtcOffset']))
    for day in {s['dateOfSleep'] for s in logs}:
        sessions = [s for s in logs if s['dateOfSleep'] == day]
        # Never promote explicit non-main sessions, but a classified nap must not
        # suppress fallback for an otherwise unclassified main sleep.
        unknown = [s for s in sessions if main_flags[s['sourceRecordId']] is None]
        if unknown and not any(s['isMainSleep'] for s in sessions):
            candidates = [s for s in unknown if staged[s['sourceRecordId']]] or unknown
            max(candidates, key=lambda s: (s['duration'], s['endInstant'], s['sourceRecordId']))['isMainSleep'] = True
    return Pending(logs) if pending else Complete(logs)


def minute_heart_rate(points):
    seen, buckets = set(), {}
    for point in points:
        hr = point['heartRate']
        sample = hr['sampleTime']
        dt = local_time(sample['physicalTime'], sample['utcOffset'])
        bpm = int(hr['beatsPerMinute'])
        if not 0 < bpm < 400:
            raise ValueError('invalid heart rate')
        identity = (dt.timestamp(), bpm)
        if identity in seen:
            continue
        seen.add(identity)
        minute = int(dt.timestamp() // 60)
        bucket = buckets.setdefault(minute, [dt, []])
        bucket[1].append(bpm)
    return [dict(time=dt.strftime('%H:%M:00'), value=math.floor(sum(values) / len(values) + .5),
                 instant=minute * 60, date=dt.date().isoformat())
            for minute, (dt, values) in sorted(buckets.items())]

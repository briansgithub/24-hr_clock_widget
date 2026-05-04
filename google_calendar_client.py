import datetime
import os.path
import os

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

# If modifying these scopes, delete the file token.json.
SCOPES = ["https://www.googleapis.com/auth/calendar.readonly"]

def get_calendar_service():
    """
    Handles authentication and returns the Google Calendar service object.
    Requires 'credentials.json' in the same directory.
    """
    creds = None
    # The file token.json stores the user's access and refresh tokens, and is
    # created automatically when the authorization flow completes for the first
    # time.
    if os.path.exists("token.json"):
        creds = Credentials.from_authorized_user_file("token.json", SCOPES)
    
    # If there are no (valid) credentials available, let the user log in.
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            if not os.path.exists("credentials.json"):
                print("Error: 'credentials.json' not found. Please download it from the Google Cloud Console.")
                return None
            
            flow = InstalledAppFlow.from_client_secrets_file(
                "credentials.json", SCOPES
            )
            creds = flow.run_local_server(port=0, open_browser=False)
        
        # Save the credentials for the next run
        with open("token.json", "w") as token:
            token.write(creds.to_json())

    try:
        service = build("calendar", "v3", credentials=creds)
        return service
    except HttpError as error:
        print(f"An error occurred: {error}")
        return None

def get_calendar_events(service, calendar_id='primary', days=1):
    """
    Fetches events for the specified number of days starting from today's midnight.
    Returns events in a format easy to convert to 0-24 float hours.
    """
    now = datetime.datetime.now(datetime.timezone.utc)
    start_of_day = now.replace(hour=0, minute=0, second=0, microsecond=0)
    end_of_period = start_of_day + datetime.timedelta(days=days)

    time_min = start_of_day.isoformat()
    time_max = end_of_period.isoformat()

    try:
        print(f"Fetching events from {time_min} to {time_max}...")
        events_result = service.events().list(
            calendarId=calendar_id,
            timeMin=time_min,
            timeMax=time_max,
            singleEvents=True,
            orderBy="startTime"
        ).execute()
        events = events_result.get("items", [])

        processed_events = []
        for event in events:
            start = event["start"].get("dateTime", event["start"].get("date"))
            end = event["end"].get("dateTime", event["end"].get("date"))
            
            # For all-day events, start/end will be YYYY-MM-DD
            # For timed events, it's ISO format
            is_all_day = "T" not in start
            
            if is_all_day:
                # Map all-day events to the full 0-24 range
                start_hour = 0.0
                end_hour = 24.0
            else:
                start_dt = datetime.datetime.fromisoformat(start.replace("Z", "+00:00"))
                end_dt = datetime.datetime.fromisoformat(end.replace("Z", "+00:00"))
                
                # We only care about the time relative to the day start for the clock
                # If the event spans multiple days, this logic might need adjustment
                # But for a 24h clock, we mostly care about "today's" portion
                start_hour = start_dt.hour + start_dt.minute / 60.0
                end_hour = end_dt.hour + end_dt.minute / 60.0
            
            processed_events.append({
                "summary": event.get("summary", "No Title"),
                "start_hour": start_hour,
                "end_hour": end_hour,
                "is_all_day": is_all_day,
                "color_id": event.get("colorId")
            })
            
        return processed_events

    except HttpError as error:
        print(f"An error occurred while fetching events: {error}")
        return []

if __name__ == "__main__":
    # Test authentication and event fetching
    svc = get_calendar_service()
    if svc:
        print("Successfully connected to Google Calendar API.")
        today_events = get_calendar_events(svc, 'primary')
        if not today_events:
            print("No events found for today.")
        else:
            print(f"Found {len(today_events)} events for today:")
            for e in today_events:
                type_str = "[ALL DAY]" if e['is_all_day'] else ""
                print(f" - {e['summary']} {type_str}: {e['start_hour']:.2f} to {e['end_hour']:.2f}")

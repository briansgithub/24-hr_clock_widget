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
            creds = flow.run_local_server(port=0)
        
        # Save the credentials for the next run
        with open("token.json", "w") as token:
            token.write(creds.to_json())

    try:
        service = build("calendar", "v3", credentials=creds)
        return service
    except HttpError as error:
        print(f"An error occurred: {error}")
        return None

def get_work_calendar_id(service):
    """
    Finds the calendar ID for the calendar named 'Work'.
    """
    try:
        calendar_list = service.calendarList().list().execute()
        for calendar_entry in calendar_list.get('items', []):
            if calendar_entry.get('summary') == 'Work':
                return calendar_entry.get('id')
        
        print("Warning: 'Work' calendar not found. Falling back to primary calendar.")
        return 'primary'
    except HttpError as error:
        print(f"An error occurred while fetching calendar list: {error}")
        return 'primary'

def get_today_events(service, calendar_id='primary'):
    """
    Fetches events for the current 24-hour period (midnight to midnight).
    Returns events in a format easy to convert to 0-24 float hours.
    """
    now = datetime.datetime.now(datetime.timezone.utc)
    # Start of today (midnight)
    start_of_day = now.replace(hour=0, minute=0, second=0, microsecond=0)
    # End of today (next midnight)
    end_of_day = start_of_day + datetime.timedelta(days=1)

    time_min = start_of_day.isoformat()
    time_max = end_of_day.isoformat()

    try:
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
            
            # Skip all-day events for now or handle them specially
            if "T" not in start:
                continue
                
            start_dt = datetime.datetime.fromisoformat(start.replace("Z", "+00:00"))
            end_dt = datetime.datetime.fromisoformat(end.replace("Z", "+00:00"))
            
            # Convert to hours past midnight (float)
            start_hour = start_dt.hour + start_dt.minute / 60.0 + start_dt.second / 3600.0
            end_hour = end_dt.hour + end_dt.minute / 60.0 + end_dt.second / 3600.0
            
            processed_events.append({
                "summary": event.get("summary", "No Title"),
                "start_hour": start_hour,
                "end_hour": end_hour,
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
        work_id = get_work_calendar_id(svc)
        print(f"Using calendar ID: {work_id}")
        today_events = get_today_events(svc, work_id)
        for e in today_events:
            print(f"{e['summary']}: {e['start_hour']:.2f} to {e['end_hour']:.2f}")

import os

def scrub(root):
    for dirpath, dirnames, filenames in os.walk(root):
        if '.git' in dirnames:
            dirnames.remove('.git')
        for filename in filenames:
            path = os.path.join(dirpath, filename)
            try:
                with open(path, 'rb') as f:
                    content = f.read()
                
                new_content = content.replace(b'YOUR_FITBIT_CLIENT_ID', b'YOUR_FITBIT_CLIENT_ID')
                new_content = new_content.replace(b'YOUR_FITBIT_CLIENT_SECRET', b'YOUR_FITBIT_CLIENT_SECRET')
                
                if new_content != content:
                    with open(path, 'wb') as f:
                        f.write(new_content)
            except Exception:
                pass

if __name__ == "__main__":
    scrub('.')

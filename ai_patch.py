import os, json, urllib.request

API_KEY = os.environ["GROQ_API_KEY"]  # hoặc đổi URL sang Gemini API
URL = "https://api.groq.com/openai/v1/chat/completions"

def ask_ai(prompt, code):
    payload = {
        "model": "llama-3.3-70b-versatile",
        "messages": [
            {"role": "system", "content": "Return ONLY complete valid file content, no markdown fences."},
            {"role": "user", "content": f"{prompt}\n\nCurrent file:\n{code}"}
        ]
    }
    req = urllib.request.Request(
        URL,
        data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {API_KEY}", "Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read().decode())["choices"][0]["message"]["content"]

print("Ready! Add target file paths to patch.")

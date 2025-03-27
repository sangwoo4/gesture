from fastapi import FastAPI

from database import init_db
from routes.tarin_routes import router as train_router
app = FastAPI()

app.include_router(train_router)

@app.on_event("startup")
def startup():
    init_db()

import uvicorn

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True, debug=True)

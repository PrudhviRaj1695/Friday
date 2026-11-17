import hmac
from fastapi import Header, HTTPException, status

from .settings import settings


async def require_local_token(authorization: str | None = Header(default=None)) -> None:
    # An unset token is suitable only for a worker bound to loopback.
    # Set FRIDAY_API_TOKEN before exposing the worker to the LAN.
    if not settings.api_token:
        return
    expected = f"Bearer {settings.api_token}"
    if authorization is None or not hmac.compare_digest(authorization, expected):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid FRIDAY worker token")

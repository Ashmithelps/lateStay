"""HTTP client for the backend's public API. The simulator talks to the backend only through this."""

from __future__ import annotations

import time
from dataclasses import dataclass

import httpx

from latestay_sim.scenarios import Scenario


class BackendError(RuntimeError):
    pass


class SessionClosed(BackendError):
    """The backend answered 409: the session has ended (agreed, no deal or expired)."""


@dataclass(frozen=True)
class Reply:
    reply: str
    offer: int
    status: str
    reason_code: str | None
    intent: str
    reply_source: str | None
    llm_calls: int
    llm_input_tokens: int
    llm_output_tokens: int
    latency_ms: float


class BackendClient:
    def __init__(self, base_url: str, timeout: float = 30.0) -> None:
        self._http = httpx.Client(base_url=base_url, timeout=timeout)

    def close(self) -> None:
        self._http.close()

    def check_health(self) -> dict:
        try:
            response = self._http.get("/api/health")
            response.raise_for_status()
        except httpx.HTTPError as e:
            raise BackendError(f"backend not reachable at {self._http.base_url}: {e}") from e
        return response.json()

    def create_session(self, scenario: Scenario) -> dict:
        body = {
            "listPrice": scenario.list_price,
            "floorPrice": scenario.floor_price,
            "slotType": scenario.slot_type,
            "arrival": scenario.arrival,
        }
        if scenario.pricing is not None:
            body |= {
                "pricingVersion": scenario.pricing.version,
                "roomsFree": scenario.pricing.rooms_free,
                "totalRooms": scenario.pricing.total_rooms,
                "demandPercent": scenario.pricing.demand_percent,
            }
        response = self._http.post("/api/dev/negotiations", json=body)
        if response.status_code == 404:
            raise BackendError(
                "POST /api/dev/negotiations returned 404: run the backend with SPRING_PROFILES_ACTIVE=local")
        if response.status_code != 201:
            raise BackendError(f"create session failed: {response.status_code} {response.text}")
        return response.json()

    def send(self, session_id: str, text: str) -> Reply:
        started = time.perf_counter()
        response = self._http.post(f"/api/negotiations/{session_id}/messages", json={"text": text})
        latency_ms = (time.perf_counter() - started) * 1000
        if response.status_code == 409:
            raise SessionClosed(response.json().get("sessionStatus", "CLOSED"))
        if response.status_code != 200:
            raise BackendError(f"message failed: {response.status_code} {response.text}")
        body = response.json()
        usage = body.get("llmUsage") or {}
        return Reply(
            reply=body["reply"],
            offer=body["offer"],
            status=body["status"],
            reason_code=body.get("reasonCode"),
            intent=body["intent"],
            reply_source=body.get("replySource"),
            llm_calls=usage.get("calls", 0),
            llm_input_tokens=usage.get("inputTokens", 0),
            llm_output_tokens=usage.get("outputTokens", 0),
            latency_ms=latency_ms,
        )

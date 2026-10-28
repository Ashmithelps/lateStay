# Simulator. The backend must be running with SPRING_PROFILES_ACTIVE=local (see sim/README.md).
N ?= 200
ARGS ?=

.PHONY: sim sim-offline sim-test

sim:
	cd sim && uv run latestay-sim --n $(N) $(ARGS)

sim-offline:
	cd sim && uv run latestay-sim --offline --n $(N) $(ARGS)

sim-test:
	cd sim && uv run pytest -q && uv run ruff check src tests

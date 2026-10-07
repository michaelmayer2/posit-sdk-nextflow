"""Unit tests for the posit-workbench-nf-launcher CLI, mocking the Workbench HTTP API."""

from __future__ import annotations

import json
import re

import pytest
import responses

from posit_workbench_nf_launcher import cli


@pytest.fixture(autouse=True)
def _admin_env(monkeypatch):
    """Force the admin (Bearer-token) client path -- there's no ambient session in a test run."""
    monkeypatch.delenv("POSIT_PRODUCT", raising=False)
    monkeypatch.delenv("RS_SERVER_ADDRESS", raising=False)
    monkeypatch.setenv("WORKBENCH_SERVER", "https://workbench.example.com")
    monkeypatch.setenv("WORKBENCH_API_KEY", "test-token")


def _rpc_url(method: str) -> re.Pattern:
    return re.compile(rf".*/api/{method}$")


@responses.activate
def test_submit_prints_job_id(capsys, tmp_path):
    script = tmp_path / "run.sh"
    script.write_text("#!/bin/bash\necho hi\n")

    responses.add(
        responses.POST,
        _rpc_url("launch_job"),
        json={"result": {"job": {"id": "42"}}},
    )

    rc = cli.main(
        [
            "submit",
            "--cluster",
            "Kubernetes",
            "--name",
            "nf-task",
            "--cpus",
            "2",
            "--mem-mb",
            "512",
            str(script),
        ]
    )

    assert rc == 0
    assert capsys.readouterr().out.strip() == "42"

    sent = json.loads(responses.calls[0].request.body)
    assert sent["method"] == "launch_job"
    job = sent["kwparams"]["job"]
    assert job["cluster"] == "Kubernetes"
    assert job["exe"] == "/bin/bash"
    assert job["args"] == [str(script)]
    assert job["resourceLimits"] == [
        {"type": "cpuCount", "value": "2"},
        {"type": "memory", "value": "512"},
    ]
    assert "resourceProfile" not in job


@responses.activate
def test_submit_passes_resource_profile(tmp_path):
    script = tmp_path / "run.sh"
    script.write_text("echo hi\n")

    responses.add(
        responses.POST,
        _rpc_url("launch_job"),
        json={"result": {"job": {"id": "43"}}},
    )

    rc = cli.main(
        [
            "submit",
            "--cluster",
            "Kubernetes",
            "--name",
            "n",
            "--resource-profile",
            "large",
            str(script),
        ]
    )

    assert rc == 0
    job = json.loads(responses.calls[0].request.body)["kwparams"]["job"]
    assert job["resourceProfile"] == "large"
    assert "resourceLimits" not in job


@responses.activate
def test_submit_with_container_reports_failure(tmp_path, capsys):
    script = tmp_path / "run.sh"
    script.write_text("echo hi\n")

    responses.add(
        responses.POST,
        _rpc_url("launch_job"),
        json={"error": {"code": 1, "message": "cluster not found"}},
    )

    rc = cli.main(
        [
            "submit",
            "--cluster",
            "bad",
            "--name",
            "n",
            "--container",
            "python:3.12-slim",
            str(script),
        ]
    )

    assert rc == 1
    assert "cluster not found" in capsys.readouterr().err


@responses.activate
def test_status_maps_workbench_states_to_queue_status(capsys):
    responses.add(
        responses.POST,
        _rpc_url("get_historical_session"),
        json={"result": {"jobs": []}},
    )
    responses.add(
        responses.POST,
        _rpc_url("get_session"),
        json={
            "result": {
                "jobs": [
                    {"id": "1", "status": "Pending"},
                    {"id": "2", "status": "Running"},
                    {"id": "3", "status": "Suspended"},
                    {"id": "4", "status": "Finished", "exitCode": 0},
                    {"id": "5", "status": "Finished", "exitCode": 1},
                    {"id": "6", "status": "Failed"},
                ]
            }
        },
    )

    rc = cli.main(["status"])

    assert rc == 0
    lines = sorted(capsys.readouterr().out.strip().splitlines())
    assert lines == [
        "1 PENDING",
        "2 RUNNING",
        "3 HOLD",
        "4 DONE",
        "5 ERROR",
        "6 ERROR",
    ]


@responses.activate
def test_kill_is_best_effort_on_already_finished_jobs():
    responses.add(responses.POST, _rpc_url("stop_job"), json={"result": None})
    responses.add(
        responses.POST,
        _rpc_url("stop_job"),
        json={"error": {"code": 2, "message": "job already finished"}},
    )

    rc = cli.main(["kill", "1", "2"])

    assert rc == 0
    assert len(responses.calls) == 2

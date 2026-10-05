"""Import actual ticket-lab snapshots into an empty repository on a disclosed schedule.

Run once in the freshly initialized destination. No checkout, deletion, history
replacement, remote configuration, or push is performed by this script.
"""

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import subprocess


SCHEDULE = [
    ("6b8d3d7b03fc1e4df09667f815233b8c0cc60646", "2026-09-15T10:00:00+05:30", 1, "Import complete booking foundation and initial learning kit"),
    ("2ee2d25da61b9ff994f219cd360a91fa625266dc", "2026-09-17T17:30:00+05:30", 1, "Add IntelliJ project study guide"),
    ("232ca72f55cefbd0a1a5f544ca44454c2527570a", "2026-09-19T16:00:00+05:30", 1, "Document locking, idempotency and retry foundations"),
    ("ea112236d1024f661d2e8500f93ea4d0ee49f649", "2026-09-22T10:00:00+05:30", 2, "Plan failure-handling scenarios and boundaries"),
    ("b7eb9a5f6b88dc196de115722a853b1497f8d352", "2026-09-23T17:00:00+05:30", 2, "Deliver API failover and graceful-drain study"),
    ("3c6f82f1d6eddab70bbfb0da3061d8886fae261f", "2026-09-24T11:00:00+05:30", 2, "Document provider-isolation handover"),
    ("f4f840b9c1fb1a3472b82227562245c4e820cffd", "2026-09-26T17:30:00+05:30", 2, "Deliver bounded provider calls, retries and admission"),
    ("0e822d8ef7da8fae6a2960d48c6d25608d7a3d7d", "2026-09-29T17:00:00+05:30", 3, "Deliver poison-job quarantine and keyed redrive"),
    ("bc7d1dd846419188a004d19777bd6c22dda5377d", "2026-10-01T17:30:00+05:30", 3, "Deliver transactional outbox and duplicate-safe inbox"),
    ("f9021f8f2443bfd6c643141220beb0100ccf0a45", "2026-10-02T11:00:00+05:30", 3, "Add event-creation API walkthrough"),
    ("973f79687dbadfd31119fbe2f926b33bb363b705", "2026-10-02T16:00:00+05:30", 3, "Add focused event-creation Postman collection"),
    ("24ddfb38a593ee8fe956acff2e38a840660a14d9", "2026-10-03T11:00:00+05:30", 3, "Add event-list API walkthrough and collection"),
    ("3f8276586d3db28565dab6438aaad99d543cb85b", "2026-10-04T15:00:00+05:30", 3, "Explain lambda execution and transaction return flow"),
    ("0980a7a769793d722a18ecfd5135678393f94872", "2026-10-05T16:00:00+05:30", 3, "Add single-event API walkthrough and collection"),
]

PROVENANCE = """# Reconstructed three-week study history

This standalone repository was created on 2026-10-05 from the ticket-booking-lab
folder in https://github.com/savi0909/java-projects.

The September 15 through October 5 author AND committer dates in the first
14 commits are deliberately assigned sprint dates. They are a reconstructed
study schedule, not evidence that development happened on those dates. The
original source commits were recorded on October 2 through October 5, 2026.

Each reconstructed commit retains its original source SHA and author date in
its message. Original verification dates and evidence stay unchanged. The first
snapshot imports the complete existing foundation; it is not a series of newly
implemented daily increments. Subsequent snapshots retain their real source
order and exact tracked contents, with this provenance file added.

Sprint 1: September 15-21, core booking and foundations.
Sprint 2: September 22-28, API failover and provider isolation.
Sprint 3: September 29-October 5, quarantine, outbox and API studies.

Later standalone setup commits use their actual creation dates.
"""


def git(repo, *args, data=None, env=None):
    return subprocess.run(
        ["git", "-C", str(repo), *args], input=data, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
    ).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("D:/java-projects"))
    parser.add_argument("--destination", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    source, destination = args.source.resolve(), args.destination.resolve()
    if source == destination:
        raise SystemExit("Source and destination must be different repositories.")
    if git(destination, "for-each-ref").strip():
        raise SystemExit("Destination must have no refs; existing history is never replaced.")
    if git(destination, "ls-files").strip():
        raise SystemExit("Destination index must be empty.")
    git(destination, "check-ref-format", "refs/heads/main")
    dates = [dt.datetime.fromisoformat(entry[1]) for entry in SCHEDULE]
    if any(left >= right for left, right in zip(dates, dates[1:])):
        raise SystemExit("Scheduled timestamps must be strictly increasing.")
    if (dates[-1].date() - dates[0].date()).days != 20:
        raise SystemExit("Schedule must span 21 inclusive calendar days.")

    # Import Git objects directly; no original working files or index are touched.
    cache = {}

    def transfer(object_id, kind):
        if object_id in cache:
            return cache[object_id]
        if kind == "blob":
            imported = git(destination, "hash-object", "-w", "--stdin",
                           data=git(source, "cat-file", "blob", object_id)).strip().decode()
        elif kind == "tree":
            entries = git(source, "ls-tree", "-z", object_id)
            for entry in entries.split(b"\0"):
                if entry:
                    metadata, _ = entry.split(b"\t", 1)
                    _, child_kind, child_id = metadata.decode().split()
                    transfer(child_id, child_kind)
            imported = git(destination, "mktree", "-z", data=entries).strip().decode()
        else:
            raise ValueError(f"Unexpected dependent object type: {kind}")
        if imported != object_id:
            raise ValueError("Imported snapshot object differs from original.")
        cache[object_id] = imported
        return imported

    provenance_id = git(destination, "hash-object", "-w", "--stdin",
                        data=PROVENANCE.encode()).strip().decode()
    parent = None
    records = []
    for source_id, timestamp, sprint, subject in SCHEDULE:
        if records:
            git(source, "merge-base", "--is-ancestor", records[-1]["sourceCommit"], source_id)
        source_tree = git(source, "rev-parse", f"{source_id}:ticket-booking-lab").strip().decode()
        transfer(source_tree, "tree")
        entries = git(source, "ls-tree", "-z", source_tree)
        if any(entry.endswith(b"\tTIMELINE_PROVENANCE.md") for entry in entries.split(b"\0")):
            raise ValueError("Source already contains a provenance file.")
        tree = git(destination, "mktree", "-z", data=entries +
                   f"100644 blob {provenance_id}\tTIMELINE_PROVENANCE.md\0".encode()).strip().decode()
        author = git(source, "show", "-s", "--format=%an%x00%ae%x00%aI", source_id).decode().strip().split("\0")
        environment = dict(os.environ, GIT_AUTHOR_NAME=author[0], GIT_AUTHOR_EMAIL=author[1],
                           GIT_AUTHOR_DATE=timestamp, GIT_COMMITTER_DATE=timestamp)
        message = (f"[reconstructed sprint {sprint}] {subject}\n\n"
                   f"Original-Commit: {source_id}\nOriginal-Author-Date: {author[2]}\n"
                   "Reconstructed-On: 2026-10-05\n"
                   "Timeline dates are assigned study milestones; source evidence dates are unchanged.\n")
        arguments = ["commit-tree", tree] + (["-p", parent] if parent else [])
        commit_id = git(destination, *arguments, data=message.encode(), env=environment).strip().decode()
        original_files = git(source, "ls-tree", "-r", "-z", source_tree).split(b"\0")
        copied_files = git(destination, "ls-tree", "-r", "-z", tree).split(b"\0")
        copied_files = [entry for entry in copied_files if not entry.endswith(b"\tTIMELINE_PROVENANCE.md")]
        if copied_files != original_files:
            raise ValueError("Reconstructed commit does not match its original project snapshot.")
        observed_dates = git(destination, "show", "-s", "--format=%aI%x00%cI", commit_id).decode().strip().split("\0")
        if observed_dates != [timestamp, timestamp]:
            raise ValueError("Git did not retain both assigned dates.")
        records.append({"sourceCommit": source_id, "sourceAuthorDate": author[2],
                        "reconstructedCommit": commit_id, "scheduledDate": timestamp,
                        "sprint": sprint, "subject": subject, "sourceProjectTree": source_tree})
        parent = commit_id
    git(destination, "update-ref", "refs/heads/main", parent, "0" * 40)
    output = destination / "docs" / "HISTORY_RECONSTRUCTION.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"reconstructedOn": "2026-10-05",
                                 "sourceRepository": "https://github.com/savi0909/java-projects",
                                 "sourcePath": "ticket-booking-lab", "commits": records}, indent=2) + "\n",
                      encoding="utf-8")
    print(json.dumps({"commits": len(records), "firstDate": SCHEDULE[0][1],
                      "lastDate": SCHEDULE[-1][1], "head": parent,
                      "verifiedExactSnapshots": len(records), "manifest": str(output)}, indent=2))


if __name__ == "__main__":
    main()

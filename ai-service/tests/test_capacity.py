from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest
from robot_service.management import export_library, restore_library
from robot_service.store import StorageCapacityError, Store, digest, uid


def test_concurrent_writes_respect_library_capacity(tmp_path):
    store = Store(tmp_path)
    store.library_limit = 100

    def write(index):
        try:
            store.write_content(tmp_path / "assets" / str(index), bytes(80))
            return True
        except StorageCapacityError:
            return False

    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(write, range(2))) == [False, True]
    assert sum(path.stat().st_size for path in (tmp_path / "assets").iterdir()) == 80


def test_upload_capacity_failure_preserves_draft(system):
    from test_library import make_book

    client, store, _, _, _, parent_headers = system
    book = make_book(client, parent_headers)
    store.library_limit = 1
    result = client.post(
        f"/v1/resources/{book['id']}/assets?purpose=pages&expectedVersion=1",
        headers=parent_headers,
        files={"file": ("page.txt", b"Hello")},
    )
    assert result.status_code == 507
    assert not store.read("SELECT * FROM assets")
    assert not list((store.root / "assets").iterdir())
    assert (
        client.get("/v1/resources/" + book["id"], headers=parent_headers).json()[
            "draft_version"
        ]
        == 1
    )


def test_restore_capacity_rolls_back_files_and_records(system, tmp_path):
    from test_library import make_book

    client, store, _, _, _, parent_headers = system
    book = make_book(client, parent_headers)
    for content in (b"one", b"two"):
        asset = uid()
        store.write_content(store.root / "assets" / asset, content)
        with store.transaction() as db:
            db.execute(
                "INSERT INTO assets VALUES(?,?,?,?,?,?)",
                (
                    asset,
                    book["id"],
                    digest(content),
                    "page.txt",
                    "text/plain",
                    len(content),
                ),
            )
    archive = export_library(store)
    target = Store(Path(tmp_path) / "restored")
    target.library_limit = 4
    with pytest.raises(StorageCapacityError):
        restore_library(target, archive)
    assert not target.read("SELECT * FROM resources")
    assert not target.read("SELECT * FROM assets")
    assert not list((target.root / "assets").iterdir())

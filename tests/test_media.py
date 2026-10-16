
import pytest
from PIL import Image

from wallpaper_manager import media
from wallpaper_manager.database import Database

from .samples import make_image, make_sample_set


@pytest.fixture
def samples(tmp_path):
    return make_sample_set(tmp_path / "media")


@pytest.fixture
def db(tmp_path):
    database = Database(tmp_path / "test.db")
    yield database
    database.close()


def test_media_type_detection():
    assert media.media_type_for(r"C:\a\b.JPG") == "image"
    assert media.media_type_for("x.webp") == "image"
    assert media.media_type_for("x.gif") == "image"
    assert media.media_type_for("x.MP4") == "video"
    assert media.media_type_for("x.webm") == "video"
    assert media.media_type_for("x.txt") is None


def test_probe_images_and_videos(samples):
    info = media.probe(str(samples["jpg"]))
    assert (info.media_type, info.width, info.height, info.duration) == ("image", 1920, 1080, None)
    info = media.probe(str(samples["mp4"]))
    assert (info.media_type, info.width, info.height) == ("video", 320, 180)
    assert info.duration == pytest.approx(2.0, abs=0.3)
    info = media.probe(str(samples["webm"]))
    assert info.media_type == "video" and info.duration == pytest.approx(1.0, abs=0.3)


def test_read_video_frame_seeks_to_timestamp(samples):
    # The sample video gets brighter over time, so a later frame has a higher red value.
    early = media.read_video_frame(str(samples["mp4"]), 0.0)
    late = media.read_video_frame(str(samples["mp4"]), 1.5)
    assert early.size == (320, 180)
    assert late.getpixel((10, 10))[0] > early.getpixel((10, 10))[0] + 100


def test_create_thumbnail_is_16_9(samples, tmp_path):
    for key in ("jpg", "gif", "mp4", "webm"):
        kind = media.media_type_for(str(samples[key]))
        name = media.create_thumbnail(str(samples[key]), kind, tmp_path / "thumbs", 1.0)
        with Image.open(tmp_path / "thumbs" / name) as thumb:
            assert thumb.size == media.THUMB_SIZE


def test_import_files_adds_skips_and_reports_failures(db, samples, tmp_path):
    broken = tmp_path / "media" / "broken.jpg"
    broken.write_bytes(b"not really a jpeg")
    notes = tmp_path / "media" / "notes.txt"
    notes.write_text("hello")

    paths = media.find_media_files(str(tmp_path / "media"))
    assert str(notes) not in paths and len(paths) == 7

    result = media.import_files(db, paths, tmp_path / "thumbs")
    assert len(result.added) == 6
    assert result.failed == [(str(broken), "not a valid image file")]

    again = media.import_files(db, paths, tmp_path / "thumbs")  # duplicates are skipped
    assert again.added == [] and again.skipped == 6

    stats = db.media_stats()
    assert stats == {"total": 6, "images": 4, "videos": 2, "favorites": 0}
    video = db.list_media(media_type="video", sort="name")[0]
    assert video.filename == "clip.mp4" and video.duration and video.file_size > 0
    assert (tmp_path / "thumbs" / video.thumbnail).exists()


def test_search_filter_sort_and_favorites(db, samples, tmp_path):
    media.import_files(db, [str(p) for p in samples.values()], tmp_path / "thumbs")
    assert [m.filename for m in db.list_media(search="CLIP", sort="name")] == ["clip.mp4", "clip.webm"]
    assert db.count_media(media_type="image") == 4

    city = db.list_media(search="city")[0]
    db.set_favorite(city.id, True)
    assert [m.filename for m in db.list_media(favorites_only=True)] == ["city.png"]
    assert db.get_media(city.id).favorite is True

    names = [m.filename for m in db.list_media(sort="name")]
    assert names == sorted(names, key=str.lower)
    page = db.list_media(sort="name", limit=2, offset=2)
    assert [m.filename for m in page] == names[2:4]


def test_remove_keeps_original_file(db, tmp_path):
    path = make_image(tmp_path / "keep.png")
    result = media.import_files(db, [str(path)], tmp_path / "thumbs")
    item = db.get_media(result.added[0])
    db.remove_media(item.id)
    media.delete_thumbnail(item, tmp_path / "thumbs")
    assert db.get_media(item.id) is None
    assert path.exists()
    assert not (tmp_path / "thumbs" / item.thumbnail).exists()


def test_ensure_thumbnail_regenerates_missing(db, tmp_path):
    path = make_image(tmp_path / "a.png")
    item = db.get_media(media.import_files(db, [str(path)], tmp_path / "thumbs").added[0])
    (tmp_path / "thumbs" / item.thumbnail).unlink()
    assert media.ensure_thumbnail(db, item, tmp_path / "thumbs").exists()

    path.unlink()  # original gone and thumbnail gone -> None, no exception
    (tmp_path / "thumbs" / item.thumbnail).unlink()
    assert media.ensure_thumbnail(db, item, tmp_path / "thumbs") is None


def test_broken_video_reports_friendly_error(db, tmp_path):
    bad = tmp_path / "bad.mp4"
    bad.write_bytes(bytes(2048))
    result = media.import_files(db, [str(bad)], tmp_path / "thumbs")
    assert result.failed == [(str(bad), "not a valid video file")]


def test_media_item_formatting(db, tmp_path):
    media_id = db.add_media(str(tmp_path / "v.mp4"), "video", 3840, 2160, 3725.4, 5 * 1024 * 1024, None)
    item = db.get_media(media_id)
    assert item.resolution_text == "3840 × 2160"
    assert item.duration_text == "1:02:05"
    assert item.size_text == "5.0 MB"

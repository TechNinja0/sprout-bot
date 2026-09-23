import asyncio

import pytest
from robot_service.priority_slot import PrioritySlot


def test_dialogue_overtakes_waiting_background_without_parallel_inference():
    async def exercise():
        slot = PrioritySlot()
        await slot.acquire()
        order = []
        active = 0

        async def run(name, background):
            nonlocal active
            await slot.acquire(background=background)
            try:
                active += 1
                assert active == 1
                order.append(name)
                await asyncio.sleep(0)
            finally:
                active -= 1
                slot.release()

        jobs = []
        for name, background in [
            ("book-1", True),
            ("book-2", True),
            ("reply-1", False),
            ("reply-2", False),
        ]:
            jobs.append(asyncio.create_task(run(name, background)))
            await asyncio.sleep(0)
        slot.release()
        await asyncio.gather(*jobs)
        assert order == ["reply-1", "reply-2", "book-1", "book-2"]
        assert not slot.locked()

    asyncio.run(exercise())


def test_timeout_does_not_release_running_inference_or_leak_waiter():
    async def exercise():
        slot = PrioritySlot()
        await slot.acquire()
        with pytest.raises(TimeoutError):
            await slot.acquire(timeout=0.01)
        assert slot.locked()
        slot.release()
        await slot.acquire(timeout=0.01)
        slot.release()
        assert not slot.locked()

    asyncio.run(exercise())


@pytest.mark.parametrize("grant_before_cancel", [False, True])
def test_cancel_racing_with_grant_returns_the_slot(grant_before_cancel):
    async def exercise():
        slot = PrioritySlot()
        await slot.acquire()
        waiter = asyncio.create_task(slot.acquire())
        await asyncio.sleep(0)
        if grant_before_cancel:
            slot.release()
            waiter.cancel()
        else:
            waiter.cancel()
            slot.release()
        with pytest.raises(asyncio.CancelledError):
            await waiter
        assert not slot.locked()
        await slot.acquire(timeout=0.01)
        slot.release()

    asyncio.run(exercise())


def test_background_cannot_fill_reserved_dialogue_waiting_slots():
    async def exercise():
        slot = PrioritySlot(max_waiters=2)
        await slot.acquire()
        background = asyncio.create_task(slot.acquire(background=True))
        await asyncio.sleep(0)
        with pytest.raises(TimeoutError):
            await slot.acquire(background=True)
        dialogue = asyncio.create_task(slot.acquire())
        await asyncio.sleep(0)
        with pytest.raises(TimeoutError):
            await slot.acquire()
        slot.release()
        await dialogue
        assert not background.done()
        slot.release()
        await background
        slot.release()

    asyncio.run(exercise())

"""单模型有界调度：已开始的推理完成后先接对话，同优先级保持先进先出。"""

import asyncio
from collections import deque


class PrioritySlot:
    def __init__(self, max_waiters=8):
        self._busy = False
        self._waiting = (deque(), deque())
        self.max_waiters = max_waiters

    def locked(self):
        return self._busy

    async def acquire(self, *, background=False, timeout=10):
        if not self._busy:
            self._busy = True
            return
        if sum(len(queue) for queue in self._waiting) >= self.max_waiters or (
            background and len(self._waiting[1]) >= self.max_waiters // 2
        ):
            raise TimeoutError("语音等待队列已满")
        future = asyncio.get_running_loop().create_future()
        queue = self._waiting[int(background)]
        queue.append(future)
        try:
            # shield让超时/取消与刚获得名额的竞态可判别，不能丢失名额。
            await asyncio.wait_for(asyncio.shield(future), timeout)
        except BaseException:
            if future.done() and not future.cancelled():
                self.release()
            else:
                future.cancel()
                queue.remove(future)
            raise

    def release(self):
        if not self._busy:
            raise RuntimeError("语音名额重复释放")
        for queue in self._waiting:
            while queue:
                future = queue.popleft()
                if not future.done():
                    future.set_result(None)
                    return
        self._busy = False

    async def __aenter__(self):
        await self.acquire()
        return self

    async def __aexit__(self, *_):
        self.release()

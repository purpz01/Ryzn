"""Generate synthetic ABCurves 2.0.0 ONNX parity fixtures."""
import argparse
import json
from pathlib import Path
import sys

parser = argparse.ArgumentParser()
parser.add_argument("upstream", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
sys.path.insert(0, str(args.upstream.resolve()))

import numpy as np
import onnxruntime
from abcurves import ContinuousPlanner
from abcurves._continuous.rng import RandomStream

schedules = {
    "stationary": [],
    "fixed": [(0, [500.0, 120.0])],
    "retarget": [
        (0, [500.0, 120.0]),
        (31999, [530.0, -20.0]),
        (32000, [600.0, -100.0]),
        (32001, [220.0, 270.0]),
    ],
    "restart": [(0, [350.0, 90.0]), (400000, [370.0, 95.0]), (1200000, [-350.0, 20.0])],
}

planner = ContinuousPlanner(
    assets=args.upstream / "models" / "continuous",
    backend="onnx",
    kernel_backend="numpy",
    seed=7,
    prewarm=False,
)

def run(schedule, cuts):
    planner.reset(seed=7)
    for time, target in schedule:
        planner.update_target(target, time)
    chunks = [planner.advance(cut) for cut in cuts]
    return (
        np.concatenate([part["time_us"] for part in chunks]),
        np.concatenate([part["xy"] for part in chunks]),
        planner.decisions,
    )

fixtures = {}
for name, schedule in schedules.items():
    end = 1800000 if name == "restart" else 640000
    times, points, decisions = run(schedule, [end])
    split_times, split_points, split_decisions = run(
        schedule, [17, 31999, 32000, 32001, 51001, 90013, 163555, end]
    )
    assert np.array_equal(times, split_times)
    assert np.array_equal(points, split_points)
    assert decisions == split_decisions
    assert np.isfinite(points).all()
    if name == "stationary":
        assert decisions == 0 and not points.any()
    else:
        assert decisions > 0 and points.any()
    fixtures[name] = {
        "receipts": schedule,
        "decisions": decisions,
        "samples": [[int(time), *point.tolist()] for time, point in zip(times, points)],
    }

planner.reset(seed=7)
planner.advance(32000)
try:
    planner.update_target([3.0, 4.0], 31999)
except ValueError:
    pass
else:
    raise AssertionError("retroactive receipt accepted")

random = RandomStream(7)
uniforms = random.uniform(3).tolist()
exponentials = random.exponential(5).tolist()
random.discard_categorical()
after_discard = random.uniform(3).tolist()
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(
    json.dumps({
        "onnxruntime": onnxruntime.__version__,
        "numpy": np.__version__,
        "seed": 7,
        "rng": {"uniforms": uniforms, "exponentials": exponentials, "afterDiscard": after_discard},
        "streams": fixtures,
    }, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
print(f"{len(fixtures)} reference streams passed batching and receipt checks")

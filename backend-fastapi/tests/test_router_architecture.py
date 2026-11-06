"""Architecture guardrails for the M1 FastAPI router split."""

import ast
from pathlib import Path


APP_ROOT = Path(__file__).resolve().parents[1] / "app"
MAIN_PATH = APP_ROOT / "main.py"
EXPECTED_ROUTER_MODULES = {
    "admin.py",
    "auth.py",
    "catalog.py",
    "crashes.py",
    "inventory.py",
    "presence.py",
    "sessions.py",
    "speech.py",
    "stats.py",
    "teams.py",
}


def _app_decorators(node: ast.FunctionDef | ast.AsyncFunctionDef) -> set[str]:
    decorators = set()
    for decorator in node.decorator_list:
        call = decorator.func if isinstance(decorator, ast.Call) else decorator
        if (
            isinstance(call, ast.Attribute)
            and isinstance(call.value, ast.Name)
            and call.value.id == "app"
        ):
            decorators.add(call.attr)
    return decorators


def test_main_is_a_small_composition_root():
    source = MAIN_PATH.read_text(encoding="utf-8")

    assert len(source.splitlines()) < 200

    tree = ast.parse(source)
    app_decorators = {
        decorator
        for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        for decorator in _app_decorators(node)
    }
    assert app_decorators == {"on_event", "websocket"}


def test_expected_domain_router_modules_exist():
    actual = {path.name for path in (APP_ROOT / "routers").glob("*.py")}

    assert EXPECTED_ROUTER_MODULES <= actual
    for module_name in EXPECTED_ROUTER_MODULES:
        source = (APP_ROOT / "routers" / module_name).read_text(encoding="utf-8")
        assert "APIRouter(" in source

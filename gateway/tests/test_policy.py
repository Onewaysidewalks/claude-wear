from hermes_gateway.policy import RiskPolicy


def test_default_patterns():
    p = RiskPolicy()
    assert p.classify("rm -rf ~/Projects") == "high"
    assert p.classify("sudo launchctl unload x") == "high"
    assert p.classify("unlock", "the front door") == "high"
    assert p.classify("send", "email to bob") == "high"
    assert p.classify("ls -la") == "normal"
    assert p.classify("read_file", "notes.md") == "normal"


def test_extra_patterns():
    p = RiskPolicy([r"\bthermostat\b"])
    assert p.classify("set thermostat 30") == "high"

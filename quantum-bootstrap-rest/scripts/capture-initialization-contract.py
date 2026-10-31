"""Project the shared initialization operation and reachable schemas from producer OpenAPI."""
import argparse, copy, hashlib, json
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('producer', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
raw = args.producer.read_bytes()
producer = json.loads(raw)
path = '/internal/tenant-initializations'
operation = producer['paths'][path]['post']
if operation.get('operationId') != 'initializeTenantApplication':
    raise SystemExit('INITIALIZATION_PRODUCER_OPERATION_MISMATCH')
result = {'openapi': producer['openapi'], 'info': {'title': 'Quantum Tenant Initialization', 'version': '1.4.2-SNAPSHOT'},
          'paths': {p: copy.deepcopy(value) for p,value in producer['paths'].items() if p == path or p.startswith(path+'/')}, 'components': {},
          'x-producer-contract-sha256': hashlib.sha256(raw).hexdigest()}
pending = [result['paths']]
seen = set()
while pending:
    value = pending.pop()
    if isinstance(value, dict):
        ref = value.get('$ref')
        if ref:
            if not ref.startswith('#/components/'):
                raise SystemExit('INITIALIZATION_EXTERNAL_REFERENCE_UNSUPPORTED')
            if ref not in seen:
                seen.add(ref)
                _, _, section, key = ref.split('/')
                schema = copy.deepcopy(producer['components'][section][key])
                result['components'].setdefault(section, {})[key] = schema
                pending.append(schema)
        pending.extend(value.values())
    elif isinstance(value, list):
        pending.extend(value)
if 'securitySchemes' in producer.get('components', {}):
    result['components']['securitySchemes'] = copy.deepcopy(producer['components']['securitySchemes'])
if 'security' in producer:
    result['security'] = copy.deepcopy(producer['security'])
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, indent=2) + '\n')

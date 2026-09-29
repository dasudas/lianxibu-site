function segment(value) {
  if (typeof value !== 'string' || value.length === 0 || value.length > 64) {
    throw new TypeError('A complete opaque ID is required');
  }
  return encodeURIComponent(value);
}

const learningKinds = Object.freeze({
  node: 'nodes',
  question: 'questions',
  project: 'projects'
});

export function learningObjectPath(packageId, kind, objectId) {
  const collection = learningKinds[kind];
  if (!collection) throw new TypeError('Unknown learning object kind');
  return `/learning/packages/${segment(packageId)}/${collection}/${segment(objectId)}`;
}

export function recordPath(kind, objectId, packageId = null) {
  if (kind === 'article') {
    if (packageId !== null) throw new TypeError('Article record must not carry a package ID');
  } else if (!learningKinds[kind] || !packageId) {
    throw new TypeError('Learning record requires its package ID');
  }
  return `/private/records/${kind}/${segment(objectId)}`;
}

export function recordWriteRequest(kind, objectId, packageId, fields) {
  const path = recordPath(kind, objectId, packageId);
  const requiredPackage = kind === 'article' ? null : packageId;
  if (Object.hasOwn(fields, 'package_id') && fields.package_id !== requiredPackage) {
    throw new TypeError('Record package ID differs from the requested identity');
  }
  if (Object.hasOwn(fields, 'object_kind') && fields.object_kind !== kind) {
    throw new TypeError('Record kind differs from the requested identity');
  }
  return { path, body: { ...fields, package_id: requiredPackage, object_kind: kind } };
}

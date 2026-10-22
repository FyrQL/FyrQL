/*
 * Copyright 2026 the FyrQL authors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

/**
 * Apache Iceberg tables as a source: the metadata file, the manifest list
 * and the manifests name the Parquet data files and delete files of a
 * snapshot, which the Parquet connector reads.
 * <p>
 * Part of the API; covered by the compatibility promise.
 * Classes marked otherwise in their documentation are the exceptions.
 */
package net.fyrql.connector.iceberg;

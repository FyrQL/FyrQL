/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.exec;

/**
 * A unit of work of a segment: a split of the scan of one of its chains.
 *
 * @param chain the chain, in the order of the inputs of a union
 * @param split the split of the scan of the chain
 * @param index the place among all morsels of the segment, in the order one thread reads them
 */
record Morsel(int chain, int split, int index) {
}

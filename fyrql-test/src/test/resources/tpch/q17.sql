-- Copyright 2026 the FyrQL authors
--
-- This Source Code Form is subject to the terms of the Mozilla Public
-- License, v. 2.0. If a copy of the MPL was not distributed with this
-- file, You can obtain one at https://mozilla.org/MPL/2.0/.
--
-- This Source Code Form is "Incompatible With Secondary Licenses", as
-- defined by the Mozilla Public License, v. 2.0.
--
-- TPC-H Q17, small-quantity-order revenue, with the validation parameters of the specification.
select
    sum(l_extendedprice) / 7.0 as avg_yearly
from
    lineitem,
    part
where
    p_partkey = l_partkey
    and p_brand = 'Brand#23'
    and p_container = 'MED BOX'
    and l_quantity < (
        select
            0.2 * avg(l_quantity)
        from
            lineitem
        where
            l_partkey = p_partkey
    )

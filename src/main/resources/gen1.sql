create table product
(
    id    serial
        primary key,
    name  varchar(64) not null,
    stock integer     not null
);

create table "order"
(
    id          serial
        primary key,
    "productId" integer                 not null
        references product,
    "userId"    integer                 not null,
    time        timestamp default now() not null
);

create unique index "order_productId_userId_uindex"
    on "order" ("productId", "userId");

create function ordering(uid integer, pid integer) returns integer
    language plpgsql
as
$$
declare
    locked integer;
    affect integer;
begin
    update public.product set stock = stock - 1
    where id = "pid" and stock > 0;

    get diagnostics locked = row_count;

    if locked = 0 then
            return null;
    end if;

    insert into "order"(id, "productId", "userId", time)
    values (default, pid, uid, now());

    get diagnostics affect = row_count;

    return affect;
end;
    $$;
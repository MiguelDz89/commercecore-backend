psql -h localhost -U postgres -p 5432

CREATE DATABASE commercecore_dev  ENCODING 'UTF8';

CREATE ROLE commercecore_dev WITH LOGIN;

\password commercecore_dev
#
ALTER DATABASE commercecore_dev OWNER TO commercecore_dev;


psql -h localhost -U commercecore_dev -p 5432

psql -h localhost -p 5432 -U commercecore_dev -d commercecore_dev -W













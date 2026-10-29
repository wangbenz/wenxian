"""按账号存放幂等公告回执；不提供公告写入或管理接口。"""


class AnnouncementRepository:
    """回执只关联账号与公告散列，绝不加载剧情。"""

    def __init__(self, repository):
        self.db = repository

    def receipts(self, account_id):
        with self.db.connection() as c:
            return {(row["release_id"], row["content_hash"]) for row in c.execute(
                "SELECT release_id,content_hash FROM announcement_receipt WHERE account_id=?", (account_id,))}

    def mark_read(self, account_id, identifier, digest, timestamp):
        with self.db.connection(write=True) as c:
            c.execute("INSERT INTO announcement_receipt VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                      (account_id, identifier, digest, timestamp))

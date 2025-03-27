from sqlalchemy import Column, Integer, String
from database import Base

class File(Base):
    __tablename__ = 'files'

    id = Column(Integer, primary_key=True, index=True)
    Model = Column(String(50), nullable=False)
    Train_Data = Column(String(50), nullable=False)
    Test_Data = Column(String(50), nullable=False)

